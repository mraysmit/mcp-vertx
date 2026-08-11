package dev.mars.mcp;

import dev.mars.mcp.tool.ToolContext;
import dev.mars.mcp.tool.Tool;
import dev.mars.mcp.tool.ToolRegistry;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link McpServerVerticle}.
 *
 * <p>Tests are organized into two groups:
 * <ul>
 *   <li>Streamable HTTP transport (2025-03-26) — the new transport</li>
 *   <li>Legacy HTTP+SSE transport (2024-11-05) — backwards compatibility</li>
 * </ul>
 */
@ExtendWith(VertxExtension.class)
class McpServerVerticleTest {

  private static final AtomicInteger SEQ = new AtomicInteger();
  private Map<String, Tool> tools;

  @BeforeEach
  void setUp(Vertx vertx) {
    Tool echoTool = new Tool() {
      @Override public String name() { return "text.echo"; }
      @Override public String description() { return "Echo a message"; }
      @Override public JsonObject schema() {
        return new JsonObject()
            .put("type", "object")
            .put("properties", new JsonObject()
                .put("message", new JsonObject().put("type", "string")))
            .put("required", new io.vertx.core.json.JsonArray().add("message"));
      }
      @Override public Future<JsonObject> invoke(JsonObject args, ToolContext ctx) {
        return Future.succeededFuture(new JsonObject()
            .put("message", args.getString("message", ""))
            .put("resourceId", ctx.resourceId()));
      }
    };
    Tool statusTool = new Tool() {
      @Override public String name() { return "server.status"; }
      @Override public String description() { return "Return server status"; }
      @Override public JsonObject schema() {
        return new JsonObject()
            .put("type", "object")
            .put("properties", new JsonObject()
                .put("resourceId", new JsonObject().put("type", "string")));
      }
      @Override public Future<JsonObject> invoke(JsonObject args, ToolContext ctx) {
        return Future.succeededFuture(new JsonObject()
            .put("status", "ok")
            .put("resourceId", ctx.resourceId()));
      }
    };
    tools = ToolRegistry.of(echoTool, statusTool);
  }

  private DeploymentOptions portZero() {
    return new DeploymentOptions().setConfig(
        new JsonObject().put("mcp.port", 0));
  }

  // ══════════════════════════════════════════════════════════════════
  // Deployment
  // ══════════════════════════════════════════════════════════════════

  @Test
  void deploys_successfully(Vertx vertx, VertxTestContext ctx) {
    vertx.deployVerticle(new McpServerVerticle(tools), portZero())
      .onSuccess(id -> ctx.completeNow())
      .onFailure(ctx::failNow);
  }

  // ══════════════════════════════════════════════════════════════════
  // Streamable HTTP Transport (2025-03-26)
  // ══════════════════════════════════════════════════════════════════

  @Nested
  class StreamableHttpTransport {

    @Test
    void initialize_returns_session_id_header(Vertx vertx, VertxTestContext ctx) {
      var verticle = new McpServerVerticle(tools, "resourceId");

      vertx.deployVerticle(verticle, portZero())
        .compose(id -> postMcp(vertx, verticle.actualPort(), null, new JsonObject()
          .put("jsonrpc", "2.0")
          .put("id", 1)
          .put("method", "initialize")
          .put("params", new JsonObject()
            .put("protocolVersion", "2025-03-26")
            .put("clientInfo", new JsonObject()
              .put("name", "test").put("version", "1.0")))))
        .onSuccess(response -> ctx.verify(() -> {
          assertEquals(200, response.statusCode());
          String sessionId = response.getHeader("Mcp-Session-Id");
          assertNotNull(sessionId, "Expected Mcp-Session-Id header");
          assertFalse(sessionId.isBlank());

          response.body().onSuccess(body -> ctx.verify(() -> {
            JsonObject json = body.toJsonObject();
            assertEquals("2.0", json.getString("jsonrpc"));
            assertEquals(1, json.getInteger("id"));
            JsonObject result = json.getJsonObject("result");
            assertEquals("2025-03-26", result.getString("protocolVersion"));
            assertEquals(McpServerVerticle.SERVER_NAME,
                result.getJsonObject("serverInfo").getString("name"));
            ctx.completeNow();
          }));
        }))
        .onFailure(ctx::failNow);
    }

    @Test
    void ping_with_session(Vertx vertx, VertxTestContext ctx) {
      var verticle = new McpServerVerticle(tools, "resourceId");

      vertx.deployVerticle(verticle, portZero())
        .compose(id -> initializeAndGetSession(vertx, verticle.actualPort()))
        .compose(sessionId -> postMcp(vertx, verticle.actualPort(), sessionId, new JsonObject()
          .put("jsonrpc", "2.0").put("id", 2).put("method", "ping")))
        .compose(HttpClientResponse::body)
        .onSuccess(body -> ctx.verify(() -> {
          JsonObject json = body.toJsonObject();
          JsonObject result = json.getJsonObject("result");
          assertNotNull(result);
          assertTrue(result.isEmpty());
          ctx.completeNow();
        }))
        .onFailure(ctx::failNow);
    }

    @Test
    void tools_list_returns_tools(Vertx vertx, VertxTestContext ctx) {
      var verticle = new McpServerVerticle(tools, "resourceId");

      vertx.deployVerticle(verticle, portZero())
        .compose(id -> initializeAndGetSession(vertx, verticle.actualPort()))
        .compose(sessionId -> postMcp(vertx, verticle.actualPort(), sessionId, new JsonObject()
          .put("jsonrpc", "2.0").put("id", 3).put("method", "tools/list")))
        .compose(HttpClientResponse::body)
        .onSuccess(body -> ctx.verify(() -> {
          JsonObject json = body.toJsonObject();
          JsonArray toolList = json.getJsonObject("result").getJsonArray("tools");
          assertEquals(2, toolList.size());
          ctx.completeNow();
        }))
        .onFailure(ctx::failNow);
    }

    @Test
    void tools_call_invokes_tool(Vertx vertx, VertxTestContext ctx) {
      var verticle = new McpServerVerticle(tools, "resourceId");

      vertx.deployVerticle(verticle, portZero())
        .compose(id -> initializeAndGetSession(vertx, verticle.actualPort()))
        .compose(sessionId -> postMcp(vertx, verticle.actualPort(), sessionId, new JsonObject()
          .put("jsonrpc", "2.0").put("id", 4)
          .put("method", "tools/call")
          .put("params", new JsonObject()
            .put("name", "server.status")
            .put("arguments", new JsonObject()
              .put("resourceId", "resource-stream-1")))))
        .compose(HttpClientResponse::body)
        .onSuccess(body -> ctx.verify(() -> {
          JsonObject json = body.toJsonObject();
          assertNull(json.getJsonObject("error"));
          JsonArray content = json.getJsonObject("result").getJsonArray("content");
          assertEquals(1, content.size());
          JsonObject toolResult = new JsonObject(content.getJsonObject(0).getString("text"));
          assertEquals("ok", toolResult.getString("status"));
          assertEquals("resource-stream-1", toolResult.getString("resourceId"));
          ctx.completeNow();
        }))
        .onFailure(ctx::failNow);
    }

    @Test
    void request_without_session_returns_400(Vertx vertx, VertxTestContext ctx) {
      var verticle = new McpServerVerticle(tools, "resourceId");

      vertx.deployVerticle(verticle, portZero())
        .compose(id -> postMcp(vertx, verticle.actualPort(), null, new JsonObject()
          .put("jsonrpc", "2.0").put("id", 1).put("method", "ping")))
        .onSuccess(response -> ctx.verify(() -> {
          assertEquals(400, response.statusCode());
          ctx.completeNow();
        }))
        .onFailure(ctx::failNow);
    }

    @Test
    void request_with_invalid_session_returns_404(Vertx vertx, VertxTestContext ctx) {
      var verticle = new McpServerVerticle(tools, "resourceId");

      vertx.deployVerticle(verticle, portZero())
        .compose(id -> postMcp(vertx, verticle.actualPort(), "invalid-session", new JsonObject()
          .put("jsonrpc", "2.0").put("id", 1).put("method", "ping")))
        .onSuccess(response -> ctx.verify(() -> {
          assertEquals(404, response.statusCode());
          ctx.completeNow();
        }))
        .onFailure(ctx::failNow);
    }

    @Test
    void notification_returns_202(Vertx vertx, VertxTestContext ctx) {
      var verticle = new McpServerVerticle(tools, "resourceId");

      vertx.deployVerticle(verticle, portZero())
        .compose(id -> postMcp(vertx, verticle.actualPort(), null, new JsonObject()
          .put("jsonrpc", "2.0")
          .put("method", "notifications/initialized")))
        .onSuccess(response -> ctx.verify(() -> {
          assertEquals(202, response.statusCode());
          ctx.completeNow();
        }))
        .onFailure(ctx::failNow);
    }

    @Test
    void batch_request(Vertx vertx, VertxTestContext ctx) {
      var verticle = new McpServerVerticle(tools, "resourceId");

      JsonArray batch = new JsonArray()
        .add(new JsonObject().put("jsonrpc", "2.0").put("id", 1).put("method", "ping"))
        .add(new JsonObject().put("jsonrpc", "2.0").put("id", 2).put("method", "tools/list"));

      vertx.deployVerticle(verticle, portZero())
        .compose(id -> initializeAndGetSession(vertx, verticle.actualPort()))
        .compose(sessionId -> postMcpBatch(vertx, verticle.actualPort(), sessionId, batch))
        .compose(HttpClientResponse::body)
        .onSuccess(body -> ctx.verify(() -> {
          JsonArray responses = body.toJsonArray();
          assertEquals(2, responses.size());
          ctx.completeNow();
        }))
        .onFailure(ctx::failNow);
    }

    @Test
    void delete_terminates_session(Vertx vertx, VertxTestContext ctx) {
      var verticle = new McpServerVerticle(tools, "resourceId");

      vertx.deployVerticle(verticle, portZero())
        .compose(id -> initializeAndGetSession(vertx, verticle.actualPort()))
        .compose(sessionId -> deleteMcp(vertx, verticle.actualPort(), sessionId)
          .compose(resp -> {
            ctx.verify(() -> assertEquals(204, resp.statusCode()));
            // Try to use deleted session
            return postMcp(vertx, verticle.actualPort(), sessionId, new JsonObject()
              .put("jsonrpc", "2.0").put("id", 1).put("method", "ping"));
          }))
        .onSuccess(response -> ctx.verify(() -> {
          assertEquals(404, response.statusCode());
          ctx.completeNow();
        }))
        .onFailure(ctx::failNow);
    }

    @Test
    void get_without_session_returns_400(Vertx vertx, VertxTestContext ctx) {
      var verticle = new McpServerVerticle(tools, "resourceId");

      vertx.deployVerticle(verticle, portZero())
        .compose(id -> vertx.createHttpClient()
          .request(HttpMethod.GET, verticle.actualPort(), "localhost", "/mcp"))
        .compose(req -> req
          .putHeader("Accept", "text/event-stream")
          .send())
        .onSuccess(response -> ctx.verify(() -> {
          assertEquals(400, response.statusCode());
          ctx.completeNow();
        }))
        .onFailure(ctx::failNow);
    }

    // ── Streamable HTTP helpers ─────────────────────────────────────

    private Future<HttpClientResponse> postMcp(Vertx vertx, int port, String sessionId, JsonObject request) {
      return vertx.createHttpClient()
        .request(HttpMethod.POST, port, "localhost", "/mcp")
        .compose(req -> {
          req.putHeader("Content-Type", "application/json")
             .putHeader("Accept", "application/json");
          if (sessionId != null) {
            req.putHeader("Mcp-Session-Id", sessionId);
          }
          return req.send(Buffer.buffer(request.encode()));
        });
    }

    private Future<HttpClientResponse> postMcpBatch(Vertx vertx, int port, String sessionId, JsonArray batch) {
      return vertx.createHttpClient()
        .request(HttpMethod.POST, port, "localhost", "/mcp")
        .compose(req -> {
          req.putHeader("Content-Type", "application/json")
             .putHeader("Accept", "application/json");
          if (sessionId != null) {
            req.putHeader("Mcp-Session-Id", sessionId);
          }
          return req.send(Buffer.buffer(batch.encode()));
        });
    }

    private Future<HttpClientResponse> deleteMcp(Vertx vertx, int port, String sessionId) {
      return vertx.createHttpClient()
        .request(HttpMethod.DELETE, port, "localhost", "/mcp")
        .compose(req -> req
          .putHeader("Mcp-Session-Id", sessionId)
          .send());
    }

    private Future<String> initializeAndGetSession(Vertx vertx, int port) {
      return vertx.createHttpClient()
        .request(HttpMethod.POST, port, "localhost", "/mcp")
        .compose(req -> req
          .putHeader("Content-Type", "application/json")
          .putHeader("Accept", "application/json")
          .send(Buffer.buffer(new JsonObject()
            .put("jsonrpc", "2.0")
            .put("id", 1)
            .put("method", "initialize")
            .put("params", new JsonObject()
              .put("protocolVersion", "2025-03-26")
              .put("clientInfo", new JsonObject()
                .put("name", "test").put("version", "1.0")))
            .encode())))
        .map(response -> response.getHeader("Mcp-Session-Id"));
    }
  }

  // ══════════════════════════════════════════════════════════════════
  // Legacy HTTP+SSE Transport (2024-11-05) — Backwards Compatibility
  // ══════════════════════════════════════════════════════════════════

  @Nested
  class LegacyHttpSseTransport {

    @Test
    void sse_endpoint_sends_endpoint_event(Vertx vertx, VertxTestContext ctx) {
      var verticle = new McpServerVerticle(tools, "resourceId");

      vertx.deployVerticle(verticle, portZero())
        .compose(id -> vertx.createHttpClient()
          .request(HttpMethod.GET, verticle.actualPort(), "localhost", "/sse"))
        .compose(HttpClientRequest::send)
        .onSuccess(response -> {
          assertEquals(200, response.statusCode());
          assertEquals("text/event-stream",
              response.getHeader("Content-Type"));

          response.handler(buffer -> ctx.verify(() -> {
            String data = buffer.toString();
            if (data.contains("event: endpoint")) {
              assertTrue(data.contains("/message?sessionId="));
              ctx.completeNow();
            }
          }));
        })
        .onFailure(ctx::failNow);
    }

    @Test
    void message_without_session_returns_400(Vertx vertx, VertxTestContext ctx) {
      var verticle = new McpServerVerticle(tools, "resourceId");

      vertx.deployVerticle(verticle, portZero())
        .compose(id -> vertx.createHttpClient()
          .request(HttpMethod.POST, verticle.actualPort(), "localhost",
              "/message"))
        .compose(req -> req
          .putHeader("content-type", "application/json")
          .send(Buffer.buffer(new JsonObject()
            .put("jsonrpc", "2.0").put("id", 1)
            .put("method", "ping").encode())))
        .onSuccess(resp -> ctx.verify(() -> {
          assertEquals(400, resp.statusCode());
          ctx.completeNow();
        }))
        .onFailure(ctx::failNow);
    }

    @Test
    void message_with_invalid_session_returns_400(Vertx vertx, VertxTestContext ctx) {
      var verticle = new McpServerVerticle(tools, "resourceId");

      vertx.deployVerticle(verticle, portZero())
        .compose(id -> vertx.createHttpClient()
          .request(HttpMethod.POST, verticle.actualPort(), "localhost",
              "/message?sessionId=nonexistent"))
        .compose(req -> req
          .putHeader("content-type", "application/json")
          .send(Buffer.buffer(new JsonObject()
            .put("jsonrpc", "2.0").put("id", 1)
            .put("method", "ping").encode())))
        .onSuccess(resp -> ctx.verify(() -> {
          assertEquals(400, resp.statusCode());
          ctx.completeNow();
        }))
        .onFailure(ctx::failNow);
    }

    @Test
    void initialize_returns_legacy_protocol_version(Vertx vertx, VertxTestContext ctx) {
      roundTrip(vertx, ctx, new JsonObject()
          .put("jsonrpc", "2.0")
          .put("id", 1)
          .put("method", "initialize")
          .put("params", new JsonObject()
            .put("protocolVersion", "2024-11-05")
            .put("clientInfo", new JsonObject()
              .put("name", "test").put("version", "1.0"))),
        response -> {
          assertEquals("2.0", response.getString("jsonrpc"));
          assertEquals(1, response.getInteger("id"));
          JsonObject result = response.getJsonObject("result");
          assertNotNull(result);
          // Legacy transport returns legacy protocol version
          assertEquals(McpServerVerticle.LEGACY_PROTOCOL_VERSION,
              result.getString("protocolVersion"));
          assertEquals(McpServerVerticle.SERVER_NAME,
              result.getJsonObject("serverInfo").getString("name"));
          assertNotNull(result.getJsonObject("capabilities")
              .getJsonObject("tools"));
        });
    }

    @Test
    void ping_returns_empty_result(Vertx vertx, VertxTestContext ctx) {
      roundTrip(vertx, ctx, new JsonObject()
          .put("jsonrpc", "2.0").put("id", 2).put("method", "ping"),
        response -> {
          JsonObject result = response.getJsonObject("result");
          assertNotNull(result);
          assertTrue(result.isEmpty());
        });
    }

    @Test
    void tools_list_returns_registered_tools(Vertx vertx, VertxTestContext ctx) {
      roundTrip(vertx, ctx, new JsonObject()
          .put("jsonrpc", "2.0").put("id", 3).put("method", "tools/list"),
        response -> {
          JsonObject result = response.getJsonObject("result");
          assertNotNull(result);
          JsonArray toolList = result.getJsonArray("tools");
          assertNotNull(toolList);
          assertEquals(2, toolList.size());

          boolean hasEcho = false, hasStatus = false;
          for (int i = 0; i < toolList.size(); i++) {
            JsonObject t = toolList.getJsonObject(i);
            assertNotNull(t.getString("name"));
            assertNotNull(t.getString("description"));
            assertNotNull(t.getJsonObject("inputSchema"));
            if ("text.echo".equals(t.getString("name"))) hasEcho = true;
            if ("server.status".equals(t.getString("name"))) hasStatus = true;
          }
          assertTrue(hasEcho, "Expected text.echo tool");
          assertTrue(hasStatus, "Expected server.status tool");
        });
    }

    @Test
    void tools_call_invokes_status(Vertx vertx, VertxTestContext ctx) {
      roundTrip(vertx, ctx, new JsonObject()
          .put("jsonrpc", "2.0").put("id", 4)
          .put("method", "tools/call")
          .put("params", new JsonObject()
            .put("name", "server.status")
            .put("arguments", new JsonObject()
              .put("resourceId", "resource-1"))),
        response -> {
          assertNull(response.getJsonObject("error"),
              () -> "Unexpected error: " + response.getJsonObject("error"));
          JsonObject result = response.getJsonObject("result");
          JsonArray content = result.getJsonArray("content");
          assertEquals(1, content.size());
          assertEquals("text", content.getJsonObject(0).getString("type"));
          JsonObject toolResult = new JsonObject(
              content.getJsonObject(0).getString("text"));
          assertEquals("ok", toolResult.getString("status"));
          assertEquals("resource-1", toolResult.getString("resourceId"));
        });
    }

    @Test
    void tools_call_echoes_message(Vertx vertx, VertxTestContext ctx) {
      roundTrip(vertx, ctx, new JsonObject()
          .put("jsonrpc", "2.0").put("id", 5)
          .put("method", "tools/call")
          .put("params", new JsonObject()
            .put("name", "text.echo")
            .put("arguments", new JsonObject()
              .put("message", "hello MCP")
              .put("resourceId", "resource-2"))),
        response -> {
          assertNull(response.getJsonObject("error"),
              () -> "Unexpected error: " + response.getJsonObject("error"));
          JsonObject result = response.getJsonObject("result");
          JsonArray content = result.getJsonArray("content");
          assertEquals(1, content.size());
          JsonObject toolResult = new JsonObject(
              content.getJsonObject(0).getString("text"));
          assertEquals("hello MCP", toolResult.getString("message"));
          assertEquals("resource-2", toolResult.getString("resourceId"));
        });
    }

    @Test
    void tools_call_unknown_tool_returns_error(Vertx vertx, VertxTestContext ctx) {
      roundTrip(vertx, ctx, new JsonObject()
          .put("jsonrpc", "2.0").put("id", 6)
          .put("method", "tools/call")
          .put("params", new JsonObject()
            .put("name", "nonexistent.tool")
            .put("arguments", new JsonObject())),
        response -> {
          assertNotNull(response.getJsonObject("error"));
          assertEquals(-32602,
              response.getJsonObject("error").getInteger("code"));
          assertTrue(response.getJsonObject("error").getString("message")
              .contains("not allowlisted"));
        });
    }

    @Test
    void tools_call_missing_name_returns_error(Vertx vertx, VertxTestContext ctx) {
      roundTrip(vertx, ctx, new JsonObject()
          .put("jsonrpc", "2.0").put("id", 7)
          .put("method", "tools/call")
          .put("params", new JsonObject()
            .put("arguments", new JsonObject())),
        response -> {
          assertNotNull(response.getJsonObject("error"));
          assertEquals(-32602,
              response.getJsonObject("error").getInteger("code"));
        });
    }

    @Test
    void unknown_method_returns_method_not_found(Vertx vertx, VertxTestContext ctx) {
      roundTrip(vertx, ctx, new JsonObject()
          .put("jsonrpc", "2.0").put("id", 8)
          .put("method", "nonexistent/method"),
        response -> {
          assertNotNull(response.getJsonObject("error"));
          assertEquals(-32601,
              response.getJsonObject("error").getInteger("code"));
        });
    }

    @Test
    void notification_returns_202(Vertx vertx, VertxTestContext ctx) {
      var verticle = new McpServerVerticle(tools, "resourceId");

      vertx.deployVerticle(verticle, portZero())
        .compose(id -> getLegacySessionId(vertx, verticle.actualPort()))
        .compose(sessionId -> vertx.createHttpClient()
          .request(HttpMethod.POST, verticle.actualPort(), "localhost",
              "/message?sessionId=" + sessionId)
          .compose(req -> req
            .putHeader("content-type", "application/json")
            .send(Buffer.buffer(new JsonObject()
              .put("jsonrpc", "2.0")
              .put("method", "notifications/initialized").encode()))))
        .onSuccess(resp -> ctx.verify(() -> {
          assertEquals(202, resp.statusCode());
          ctx.completeNow();
        }))
        .onFailure(ctx::failNow);
    }

    // ── Legacy helpers ──────────────────────────────────────────────

    /**
     * Deploy → open SSE → send JSON-RPC → receive response via SSE →
     * run assertions.
     */
    private void roundTrip(Vertx vertx, VertxTestContext ctx,
                           JsonObject jsonRpcRequest,
                           java.util.function.Consumer<JsonObject> assertions) {
      var verticle = new McpServerVerticle(tools, "resourceId");

      vertx.deployVerticle(verticle, portZero())
        .compose(id -> sseRoundTrip(vertx, verticle.actualPort(), jsonRpcRequest))
        .onSuccess(response -> ctx.verify(() -> {
          assertions.accept(response);
          ctx.completeNow();
        }))
        .onFailure(ctx::failNow);
    }

    /**
     * Opens an SSE connection, extracts the session ID, sends a JSON-RPC
     * POST, and resolves with the JSON-RPC response received via SSE.
     */
    private Future<JsonObject> sseRoundTrip(Vertx vertx, int port,
                                            JsonObject jsonRpcRequest) {
      Promise<JsonObject> promise = Promise.promise();

      vertx.createHttpClient()
        .request(HttpMethod.GET, port, "localhost", "/sse")
        .compose(HttpClientRequest::send)
        .onSuccess(sseResponse -> {
          AtomicReference<String> sessionRef = new AtomicReference<>();
          StringBuilder buf = new StringBuilder();

          sseResponse.handler(buffer -> {
            buf.append(buffer.toString());
            String text = buf.toString();

            if (sessionRef.get() == null) {
              // Extract session ID from endpoint event
              int idx = text.indexOf("data: /message?sessionId=");
              if (idx >= 0) {
                String after = text.substring(
                    idx + "data: /message?sessionId=".length());
                String sessionId = after.split("[\\s\\n]")[0].trim();
                sessionRef.set(sessionId);
                buf.setLength(0);

                // POST the JSON-RPC request
                vertx.createHttpClient()
                  .request(HttpMethod.POST, port, "localhost",
                      "/message?sessionId=" + sessionId)
                  .compose(req -> req
                    .putHeader("content-type", "application/json")
                    .send(Buffer.buffer(jsonRpcRequest.encode())))
                  .onFailure(promise::fail);
              }
            } else {
              // Extract JSON-RPC response from message event
              int msgIdx = text.indexOf("event: message\ndata: ");
              if (msgIdx >= 0) {
                String json = text.substring(
                    msgIdx + "event: message\ndata: ".length()).trim();
                int end = json.indexOf("\n\n");
                if (end > 0) json = json.substring(0, end);
                try {
                  promise.tryComplete(new JsonObject(json));
                } catch (Exception e) {
                  promise.tryFail("Bad JSON-RPC response: " + json);
                }
              }
            }
          });
        })
        .onFailure(promise::fail);

      return promise.future();
    }

    /** Opens SSE and returns the session ID. */
    private Future<String> getLegacySessionId(Vertx vertx, int port) {
      Promise<String> promise = Promise.promise();

      vertx.createHttpClient()
        .request(HttpMethod.GET, port, "localhost", "/sse")
        .compose(HttpClientRequest::send)
        .onSuccess(resp -> resp.handler(buffer -> {
          String data = buffer.toString();
          int idx = data.indexOf("sessionId=");
          if (idx >= 0) {
            String sessionId = data.substring(
                idx + "sessionId=".length()).split("[\\s\\n]")[0].trim();
            promise.tryComplete(sessionId);
          }
        }))
        .onFailure(promise::fail);

      return promise.future();
    }
  }
}
