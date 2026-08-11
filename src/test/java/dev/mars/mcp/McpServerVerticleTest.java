package dev.mars.mcp;

import dev.mars.mcp.tool.Tool;
import dev.mars.mcp.tool.ToolContext;
import dev.mars.mcp.tool.ToolRegistry;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(VertxExtension.class)
class McpServerVerticleTest {

  private Map<String, Tool> tools;

  @BeforeEach
  void setUp() {
    Tool echo = new Tool() {
      @Override public String name() { return "text.echo"; }
      @Override public String description() { return "Echo a message"; }
      @Override public JsonObject schema() {
        return new JsonObject()
            .put("type", "object")
            .put("properties", new JsonObject()
                .put("message", new JsonObject().put("type", "string")))
            .put("required", new JsonArray().add("message"))
            .put("additionalProperties", false);
      }
      @Override public Future<JsonObject> invoke(JsonObject args, ToolContext context) {
        return Future.succeededFuture(new JsonObject()
            .put("message", args.getString("message"))
            .put("resourceId", context.resourceId()));
      }
    };
    Tool status = new Tool() {
      @Override public String name() { return "server.status"; }
      @Override public JsonObject schema() {
        return new JsonObject().put("type", "object");
      }
      @Override public Future<JsonObject> invoke(JsonObject args, ToolContext context) {
        return Future.succeededFuture(new JsonObject().put("status", "ok"));
      }
    };
    Tool failing = new Tool() {
      @Override public String name() { return "test.fail"; }
      @Override public JsonObject schema() {
        return new JsonObject().put("type", "object");
      }
      @Override public Future<JsonObject> invoke(JsonObject args, ToolContext context) {
        return Future.failedFuture("upstream unavailable");
      }
    };
    Tool hanging = new Tool() {
      @Override public String name() { return "test.hang"; }
      @Override public JsonObject schema() {
        return new JsonObject().put("type", "object");
      }
      @Override public Future<JsonObject> invoke(JsonObject args, ToolContext context) {
        return Promise.<JsonObject>promise().future();
      }
    };
    tools = ToolRegistry.of(echo, status, failing, hanging);
  }

  @Test
  void deploys_on_loopback_by_default(Vertx vertx, VertxTestContext ctx) {
    deploy(vertx, new JsonObject())
        .onSuccess(server -> ctx.verify(() -> {
          assertTrue(server.actualPort() > 0);
          ctx.completeNow();
        }))
        .onFailure(ctx::failNow);
  }

  @Test
  void rejects_non_loopback_binding_without_authentication(Vertx vertx, VertxTestContext ctx) {
    deploy(vertx, new JsonObject().put("mcp.host", "0.0.0.0"))
        .onSuccess(server -> ctx.failNow("Deployment should have failed"))
        .onFailure(error -> ctx.verify(() -> {
          assertTrue(error.getMessage().contains("authToken"));
          ctx.completeNow();
        }));
  }

  @Test
  void rejects_external_schema_references() {
    Tool unsafe = new Tool() {
      @Override public String name() { return "unsafe.schema"; }
      @Override public JsonObject schema() {
        return new JsonObject().put("$ref", "https://example.com/schema.json");
      }
      @Override public Future<JsonObject> invoke(JsonObject args, ToolContext context) {
        return Future.succeededFuture(new JsonObject());
      }
    };

    IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
        () -> new McpServerVerticle(ToolRegistry.of(unsafe)));
    assertTrue(error.getMessage().contains("external $ref"));
  }

  @Test
  void rejects_unimplemented_custom_parameter_headers() {
    Tool unsupported = new Tool() {
      @Override public String name() { return "custom.header"; }
      @Override public JsonObject schema() {
        return new JsonObject()
            .put("type", "object")
            .put("properties", new JsonObject().put("region", new JsonObject()
                .put("type", "string")
                .put("x-mcp-header", "Region")));
      }
      @Override public Future<JsonObject> invoke(JsonObject args, ToolContext context) {
        return Future.succeededFuture(new JsonObject());
      }
    };

    IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
        () -> new McpServerVerticle(ToolRegistry.of(unsupported)));
    assertTrue(error.getMessage().contains("x-mcp-header"));
  }

  @Test
  void server_discover_reports_current_protocol(Vertx vertx, VertxTestContext ctx) {
    deployAndPost(vertx, modernRequest("server/discover", "discover-1", new JsonObject()))
        .compose(Response::json)
        .onSuccess(json -> ctx.verify(() -> {
          JsonObject result = json.getJsonObject("result");
          assertEquals("complete", result.getString("resultType"));
          assertEquals(McpServerVerticle.PROTOCOL_VERSION,
              result.getJsonArray("supportedVersions").getString(0));
          assertEquals(McpServerVerticle.SERVER_NAME,
              result.getJsonObject("_meta")
                  .getJsonObject("io.modelcontextprotocol/serverInfo")
                  .getString("name"));
          ctx.completeNow();
        }))
        .onFailure(ctx::failNow);
  }

  @Test
  void ping_returns_complete_result(Vertx vertx, VertxTestContext ctx) {
    deployAndPost(vertx, modernRequest("ping", 1, new JsonObject()))
        .compose(Response::json)
        .onSuccess(json -> ctx.verify(() -> {
          assertEquals("complete", json.getJsonObject("result").getString("resultType"));
          ctx.completeNow();
        }))
        .onFailure(ctx::failNow);
  }

  @Test
  void tools_list_is_deterministic(Vertx vertx, VertxTestContext ctx) {
    deployAndPost(vertx, modernRequest("tools/list", 2, new JsonObject()))
        .compose(Response::json)
        .onSuccess(json -> ctx.verify(() -> {
          JsonArray listed = json.getJsonObject("result").getJsonArray("tools");
          assertEquals(4, listed.size());
          assertEquals("server.status", listed.getJsonObject(0).getString("name"));
          assertEquals("test.fail", listed.getJsonObject(1).getString("name"));
          assertEquals("test.hang", listed.getJsonObject(2).getString("name"));
          assertEquals("text.echo", listed.getJsonObject(3).getString("name"));
          ctx.completeNow();
        }))
        .onFailure(ctx::failNow);
  }

  @Test
  void tools_call_returns_text_and_structured_content(Vertx vertx, VertxTestContext ctx) {
    JsonObject params = new JsonObject()
        .put("name", "text.echo")
        .put("arguments", new JsonObject().put("message", "hello"));
    deployAndPost(vertx, modernRequest("tools/call", 3, params))
        .compose(Response::json)
        .onSuccess(json -> ctx.verify(() -> {
          JsonObject result = json.getJsonObject("result");
          assertFalse(result.getBoolean("isError"));
          assertEquals("hello", result.getJsonObject("structuredContent").getString("message"));
          assertEquals("text", result.getJsonArray("content").getJsonObject(0).getString("type"));
          ctx.completeNow();
        }))
        .onFailure(ctx::failNow);
  }

  @Test
  void validates_tool_arguments_against_advertised_schema(Vertx vertx, VertxTestContext ctx) {
    JsonObject params = new JsonObject()
        .put("name", "text.echo")
        .put("arguments", new JsonObject().put("message", 42));
    deployAndPost(vertx, modernRequest("tools/call", 4, params))
        .compose(response -> expectJson(response, 200))
        .onSuccess(json -> ctx.verify(() -> {
          JsonObject result = json.getJsonObject("result");
          assertTrue(result.getBoolean("isError"));
          assertTrue(result.getJsonArray("content").getJsonObject(0).getString("text")
              .startsWith("Invalid tool arguments:"));
          ctx.completeNow();
        }))
        .onFailure(ctx::failNow);
  }

  @Test
  void tool_failures_are_mcp_error_results(Vertx vertx, VertxTestContext ctx) {
    JsonObject params = new JsonObject().put("name", "test.fail").put("arguments", new JsonObject());
    deployAndPost(vertx, modernRequest("tools/call", 5, params))
        .compose(Response::json)
        .onSuccess(json -> ctx.verify(() -> {
          JsonObject result = json.getJsonObject("result");
          assertTrue(result.getBoolean("isError"));
          assertEquals("upstream unavailable",
              result.getJsonArray("content").getJsonObject(0).getString("text"));
          ctx.completeNow();
        }))
        .onFailure(ctx::failNow);
  }

  @Test
  void tool_calls_time_out(Vertx vertx, VertxTestContext ctx) {
    JsonObject config = new JsonObject().put("mcp.toolTimeoutMs", 10);
    JsonObject params = new JsonObject().put("name", "test.hang").put("arguments", new JsonObject());
    deploy(vertx, config)
        .compose(server -> post(vertx, server.actualPort(), modernRequest("tools/call", 6, params)))
        .compose(Response::json)
        .onSuccess(json -> ctx.verify(() -> {
          assertTrue(json.getJsonObject("result").getBoolean("isError"));
          ctx.completeNow();
        }))
        .onFailure(ctx::failNow);
  }

  @Test
  void malformed_json_returns_parse_error_with_null_id(Vertx vertx, VertxTestContext ctx) {
    deploy(vertx, new JsonObject())
        .compose(server -> postRaw(vertx, server.actualPort(), "{bad", baseHeaders("ping")))
        .compose(response -> expectJson(response, 400))
        .onSuccess(json -> ctx.verify(() -> {
          assertTrue(json.containsKey("id"));
          assertNull(json.getValue("id"));
          assertEquals(-32700, json.getJsonObject("error").getInteger("code"));
          ctx.completeNow();
        }))
        .onFailure(ctx::failNow);
  }

  @Test
  void rejects_batches_without_throwing_500(Vertx vertx, VertxTestContext ctx) {
    deploy(vertx, new JsonObject())
        .compose(server -> postRaw(vertx, server.actualPort(), "[1]", baseHeaders("ping")))
        .compose(response -> expectJson(response, 400))
        .onSuccess(json -> ctx.verify(() -> {
          assertEquals(-32600, json.getJsonObject("error").getInteger("code"));
          ctx.completeNow();
        }))
        .onFailure(ctx::failNow);
  }

  @Test
  void rejects_non_object_params_without_throwing_500(Vertx vertx, VertxTestContext ctx) {
    JsonObject request = new JsonObject()
        .put("jsonrpc", "2.0").put("id", 7).put("method", "ping").put("params", "bad");
    deploy(vertx, new JsonObject())
        .compose(server -> post(vertx, server.actualPort(), request))
        .compose(response -> expectJson(response, 400))
        .onSuccess(json -> ctx.verify(() -> {
          assertEquals(-32602, json.getJsonObject("error").getInteger("code"));
          ctx.completeNow();
        }))
        .onFailure(ctx::failNow);
  }

  @Test
  void rejects_missing_jsonrpc_version(Vertx vertx, VertxTestContext ctx) {
    JsonObject request = modernRequest("ping", 8, new JsonObject());
    request.remove("jsonrpc");
    deployAndPost(vertx, request)
        .compose(response -> expectJson(response, 400))
        .onSuccess(json -> ctx.verify(() -> {
          assertEquals(-32600, json.getJsonObject("error").getInteger("code"));
          ctx.completeNow();
        }))
        .onFailure(ctx::failNow);
  }

  @Test
  void rejects_missing_request_metadata(Vertx vertx, VertxTestContext ctx) {
    JsonObject request = new JsonObject()
        .put("jsonrpc", "2.0").put("id", 9).put("method", "ping")
        .put("params", new JsonObject());
    deployAndPost(vertx, request)
        .compose(response -> expectJson(response, 400))
        .onSuccess(json -> ctx.verify(() -> {
          assertEquals(-32020, json.getJsonObject("error").getInteger("code"));
          ctx.completeNow();
        }))
        .onFailure(ctx::failNow);
  }

  @Test
  void reports_unsupported_protocol_versions(Vertx vertx, VertxTestContext ctx) {
    JsonObject request = modernRequest("ping", 10, new JsonObject());
    request.getJsonObject("params").getJsonObject("_meta")
        .put("io.modelcontextprotocol/protocolVersion", "1900-01-01");
    JsonObject headers = baseHeaders("ping").put("MCP-Protocol-Version", "1900-01-01");
    deploy(vertx, new JsonObject())
        .compose(server -> postRaw(vertx, server.actualPort(), request.encode(), headers))
        .compose(response -> expectJson(response, 400))
        .onSuccess(json -> ctx.verify(() -> {
          JsonObject error = json.getJsonObject("error");
          assertEquals(-32022, error.getInteger("code"));
          assertEquals(McpServerVerticle.PROTOCOL_VERSION,
              error.getJsonObject("data").getJsonArray("supported").getString(0));
          ctx.completeNow();
        }))
        .onFailure(ctx::failNow);
  }

  @Test
  void rejects_header_body_version_mismatch(Vertx vertx, VertxTestContext ctx) {
    JsonObject request = modernRequest("ping", 11, new JsonObject());
    JsonObject headers = baseHeaders("ping").put("MCP-Protocol-Version", "1900-01-01");
    deploy(vertx, new JsonObject())
        .compose(server -> postRaw(vertx, server.actualPort(), request.encode(), headers))
        .compose(response -> expectJson(response, 400))
        .onSuccess(json -> ctx.verify(() -> {
          assertEquals(-32020, json.getJsonObject("error").getInteger("code"));
          ctx.completeNow();
        }))
        .onFailure(ctx::failNow);
  }

  @Test
  void rejects_mirrored_method_mismatch_with_header_error(Vertx vertx, VertxTestContext ctx) {
    JsonObject request = modernRequest("ping", 20, new JsonObject());
    JsonObject headers = baseHeaders("tools/list");
    deploy(vertx, new JsonObject())
        .compose(server -> postRaw(vertx, server.actualPort(), request.encode(), headers))
        .compose(response -> expectJson(response, 400))
        .onSuccess(json -> ctx.verify(() -> {
          assertEquals(-32020, json.getJsonObject("error").getInteger("code"));
          ctx.completeNow();
        }))
        .onFailure(ctx::failNow);
  }

  @Test
  void decodes_base64_mirrored_tool_names(Vertx vertx, VertxTestContext ctx) {
    JsonObject params = new JsonObject()
        .put("name", "text.echo")
        .put("arguments", new JsonObject().put("message", "encoded header"));
    JsonObject request = modernRequest("tools/call", 21, params);
    String encodedName = Base64.getEncoder()
        .encodeToString("text.echo".getBytes(StandardCharsets.UTF_8));
    JsonObject headers = baseHeaders("tools/call")
        .put("Mcp-Name", "=?base64?" + encodedName + "?=");
    deploy(vertx, new JsonObject())
        .compose(server -> postRaw(vertx, server.actualPort(), request.encode(), headers))
        .compose(response -> expectJson(response, 200))
        .onSuccess(json -> ctx.verify(() -> {
          assertFalse(json.getJsonObject("result").getBoolean("isError"));
          ctx.completeNow();
        }))
        .onFailure(ctx::failNow);
  }

  @Test
  void rejects_malformed_client_capabilities_without_a_500(Vertx vertx,
                                                              VertxTestContext ctx) {
    JsonObject request = modernRequest("ping", 22, new JsonObject());
    request.getJsonObject("params").getJsonObject("_meta")
        .put("io.modelcontextprotocol/clientCapabilities", "bad");
    deployAndPost(vertx, request)
        .compose(response -> expectJson(response, 400))
        .onSuccess(json -> ctx.verify(() -> {
          assertEquals(-32602, json.getJsonObject("error").getInteger("code"));
          ctx.completeNow();
        }))
        .onFailure(ctx::failNow);
  }

  @Test
  void rejects_content_type_other_than_json(Vertx vertx, VertxTestContext ctx) {
    JsonObject request = modernRequest("ping", 12, new JsonObject());
    JsonObject headers = baseHeaders("ping").put("Content-Type", "text/plain");
    deploy(vertx, new JsonObject())
        .compose(server -> postRaw(vertx, server.actualPort(), request.encode(), headers))
        .onSuccess(response -> ctx.verify(() -> {
          assertEquals(415, response.status());
          ctx.completeNow();
        }))
        .onFailure(ctx::failNow);
  }

  @Test
  void requires_both_mcp_accept_types(Vertx vertx, VertxTestContext ctx) {
    JsonObject request = modernRequest("ping", 13, new JsonObject());
    JsonObject headers = baseHeaders("ping").put("Accept", "application/json");
    deploy(vertx, new JsonObject())
        .compose(server -> postRaw(vertx, server.actualPort(), request.encode(), headers))
        .onSuccess(response -> ctx.verify(() -> {
          assertEquals(406, response.status());
          ctx.completeNow();
        }))
        .onFailure(ctx::failNow);
  }

  @Test
  void rejects_untrusted_origins(Vertx vertx, VertxTestContext ctx) {
    JsonObject request = modernRequest("ping", 14, new JsonObject());
    JsonObject headers = baseHeaders("ping").put("Origin", "https://example.com");
    deploy(vertx, new JsonObject())
        .compose(server -> postRaw(vertx, server.actualPort(), request.encode(), headers))
        .onSuccess(response -> ctx.verify(() -> {
          assertEquals(403, response.status());
          ctx.completeNow();
        }))
        .onFailure(ctx::failNow);
  }

  @Test
  void allows_explicit_origins(Vertx vertx, VertxTestContext ctx) {
    JsonObject config = new JsonObject()
        .put("mcp.allowedOrigins", new JsonArray().add("https://client.example"));
    JsonObject request = modernRequest("ping", 15, new JsonObject());
    JsonObject headers = baseHeaders("ping").put("Origin", "https://client.example");
    deploy(vertx, config)
        .compose(server -> postRaw(vertx, server.actualPort(), request.encode(), headers))
        .onSuccess(response -> ctx.verify(() -> {
          assertEquals(200, response.status());
          assertEquals("https://client.example", response.header("Access-Control-Allow-Origin"));
          ctx.completeNow();
        }))
        .onFailure(ctx::failNow);
  }

  @Test
  void enforces_bearer_authentication(Vertx vertx, VertxTestContext ctx) {
    JsonObject config = new JsonObject().put("mcp.authToken", "secret");
    JsonObject request = modernRequest("ping", 16, new JsonObject());
    deploy(vertx, config)
        .compose(server -> postRaw(vertx, server.actualPort(), request.encode(), baseHeaders("ping"))
            .compose(first -> {
              assertEquals(401, first.status());
              JsonObject authorized = baseHeaders("ping").put("Authorization", "Bearer secret");
              return postRaw(vertx, server.actualPort(), request.encode(), authorized);
            }))
        .onSuccess(response -> ctx.verify(() -> {
          assertEquals(200, response.status());
          ctx.completeNow();
        }))
        .onFailure(ctx::failNow);
  }

  @Test
  void rate_limits_clients(Vertx vertx, VertxTestContext ctx) {
    JsonObject config = new JsonObject().put("mcp.maxRequestsPerMinute", 1);
    JsonObject request = modernRequest("ping", 17, new JsonObject());
    deploy(vertx, config)
        .compose(server -> post(vertx, server.actualPort(), request)
            .compose(first -> {
              assertEquals(200, first.status());
              return post(vertx, server.actualPort(), request);
            }))
        .onSuccess(response -> ctx.verify(() -> {
          assertEquals(429, response.status());
          ctx.completeNow();
        }))
        .onFailure(ctx::failNow);
  }

  @Test
  void unknown_methods_use_http_404_and_jsonrpc_error(Vertx vertx, VertxTestContext ctx) {
    deployAndPost(vertx, modernRequest("unknown/method", 18, new JsonObject()))
        .compose(response -> expectJson(response, 404))
        .onSuccess(json -> ctx.verify(() -> {
          assertEquals(-32601, json.getJsonObject("error").getInteger("code"));
          ctx.completeNow();
        }))
        .onFailure(ctx::failNow);
  }

  @Test
  void request_methods_require_an_id(Vertx vertx, VertxTestContext ctx) {
    JsonObject request = modernRequest("tools/list", null, new JsonObject());
    request.remove("id");
    deployAndPost(vertx, request)
        .onSuccess(response -> ctx.verify(() -> {
          assertEquals(400, response.status());
          ctx.completeNow();
        }))
        .onFailure(ctx::failNow);
  }

  @Test
  void get_is_not_part_of_current_streamable_http(Vertx vertx, VertxTestContext ctx) {
    deploy(vertx, new JsonObject())
        .compose(server -> vertx.createHttpClient()
            .request(HttpMethod.GET, server.actualPort(), "127.0.0.1", "/mcp")
            .compose(request -> request.send()))
        .onSuccess(response -> ctx.verify(() -> {
          assertEquals(405, response.statusCode());
          ctx.completeNow();
        }))
        .onFailure(ctx::failNow);
  }

  @Test
  void supports_a_configured_base_path(Vertx vertx, VertxTestContext ctx) {
    JsonObject config = new JsonObject().put("mcp.basePath", "/api/");
    JsonObject request = modernRequest("ping", 19, new JsonObject());
    deploy(vertx, config)
        .compose(server -> postRaw(vertx, server.actualPort(), "/api/mcp",
            request.encode(), baseHeaders("ping")))
        .onSuccess(response -> ctx.verify(() -> {
          assertEquals(200, response.status());
          ctx.completeNow();
        }))
        .onFailure(ctx::failNow);
  }

  private Future<McpServerVerticle> deploy(Vertx vertx, JsonObject config) {
    McpServerVerticle server = new McpServerVerticle(tools, "resourceId");
    JsonObject deploymentConfig = config.copy().put("mcp.port", 0);
    return vertx.deployVerticle(server, new DeploymentOptions().setConfig(deploymentConfig))
        .map(ignored -> server);
  }

  private Future<Response> deployAndPost(Vertx vertx, JsonObject request) {
    return deploy(vertx, new JsonObject())
        .compose(server -> post(vertx, server.actualPort(), request));
  }

  private Future<Response> post(Vertx vertx, int port, JsonObject request) {
    String method = request.getString("method", "ping");
    JsonObject headers = baseHeaders(method);
    if ("tools/call".equals(method)) {
      JsonObject params = request.getJsonObject("params", new JsonObject());
      String name = params.getString("name");
      if (name != null) headers.put("Mcp-Name", name);
    }
    return postRaw(vertx, port, request.encode(), headers);
  }

  private Future<Response> postRaw(Vertx vertx, int port, String body, JsonObject headers) {
    return postRaw(vertx, port, "/mcp", body, headers);
  }

  private Future<Response> postRaw(Vertx vertx, int port, String path,
                                   String body, JsonObject headers) {
    return vertx.createHttpClient()
        .request(HttpMethod.POST, port, "127.0.0.1", path)
        .compose(request -> {
          headers.forEach(entry -> request.putHeader(entry.getKey(), String.valueOf(entry.getValue())));
          return request.send(Buffer.buffer(body));
        })
        .map(Response::new);
  }

  private JsonObject modernRequest(String method, Object id, JsonObject params) {
    JsonObject meta = new JsonObject()
        .put("io.modelcontextprotocol/protocolVersion", McpServerVerticle.PROTOCOL_VERSION)
        .put("io.modelcontextprotocol/clientInfo",
            new JsonObject().put("name", "test-client").put("version", "1.0"))
        .put("io.modelcontextprotocol/clientCapabilities", new JsonObject());
    return new JsonObject()
        .put("jsonrpc", "2.0")
        .put("id", id)
        .put("method", method)
        .put("params", params.copy().put("_meta", meta));
  }

  private JsonObject baseHeaders(String method) {
    return new JsonObject()
        .put("Content-Type", "application/json")
        .put("Accept", "application/json, text/event-stream")
        .put("MCP-Protocol-Version", McpServerVerticle.PROTOCOL_VERSION)
        .put("Mcp-Method", method);
  }

  private Future<JsonObject> expectJson(Response response, int expectedStatus) {
    assertEquals(expectedStatus, response.status());
    return response.json();
  }

  private record Response(HttpClientResponse raw) {
    int status() { return raw.statusCode(); }
    String header(String name) { return raw.getHeader(name); }
    Future<JsonObject> json() { return raw.body().map(Buffer::toJsonObject); }
  }
}
