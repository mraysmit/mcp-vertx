package dev.mars.mcp;

import dev.mars.mcp.tool.Tool;
import dev.mars.mcp.tool.ToolContext;
import dev.mars.mcp.tool.InputRequiredToolResult;
import dev.mars.mcp.tool.ToolDefinition;
import dev.mars.mcp.tool.ToolExecutionException;
import dev.mars.mcp.tool.ToolInvocation;
import dev.mars.mcp.tool.ToolResult;
import dev.mars.mcp.tool.ToolRegistry;
import dev.mars.mcp.testing.TestLoggingExtension;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpClient;
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
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith({VertxExtension.class, TestLoggingExtension.class})
class McpServerVerticleTest {

  private static final Logger LOG = LoggerFactory.getLogger(McpServerVerticleTest.class);

  private Map<String, Tool> tools;
  private HttpClient httpClient;

  @BeforeEach
  void setUp(Vertx vertx) {
    LOG.atDebug().log(() -> "Creating MCP integration-test HTTP client: vertx=" + vertx);
    httpClient = vertx.createHttpClient();
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
    LOG.atDebug().log(() -> "Prepared MCP integration-test tools: " + tools.keySet());
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
  void enforces_custom_parameter_headers(Vertx vertx, VertxTestContext ctx) {
    Tool custom = new Tool() {
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

    tools = ToolRegistry.of(custom);
    JsonObject params = new JsonObject().put("name", "custom.header")
        .put("arguments", new JsonObject().put("region", "eu-west-1"));
    JsonObject request = modernRequest("tools/call", "header-1", params);
    deploy(vertx, new JsonObject())
        .compose(server -> postRaw(vertx, server.actualPort(), request.encode(),
            baseHeaders("tools/call").put("Mcp-Name", "custom.header")
                .put("Mcp-Param-Region", "eu-west-1")))
        .compose(Response::json)
        .onSuccess(json -> ctx.verify(() -> {
          assertFalse(json.getJsonObject("result").getBoolean("isError"));
          ctx.completeNow();
        }))
        .onFailure(ctx::failNow);
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
  void obsolete_ping_is_not_advertised_or_implemented(Vertx vertx, VertxTestContext ctx) {
    deployAndPost(vertx, modernRequest("ping", 1, new JsonObject()))
        .compose(Response::json)
        .onSuccess(json -> ctx.verify(() -> {
          assertEquals(-32601, json.getJsonObject("error").getInteger("code"));
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
          assertEquals("Tool execution failed",
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
  void tool_timeouts_signal_context_and_provider_cancellation(Vertx vertx,
                                                              VertxTestContext ctx) {
    AtomicBoolean contextCancelled = new AtomicBoolean();
    AtomicBoolean providerCancelled = new AtomicBoolean();
    Tool cancellable = new Tool() {
      @Override public String name() { return "test.cancel"; }
      @Override public ToolInvocation invokeManaged(JsonObject args, ToolContext context) {
        Promise<ToolResult> result = Promise.promise();
        return new ToolInvocation(result.future(), () -> {
          contextCancelled.set(context.isCancelled());
          providerCancelled.set(true);
          result.tryFail("cancelled");
          return Future.succeededFuture();
        });
      }
    };
    tools = ToolRegistry.of(cancellable);
    JsonObject params = new JsonObject().put("name", "test.cancel")
        .put("arguments", new JsonObject());
    JsonObject config = new JsonObject().put("mcp.toolTimeoutMs", 10)
        .put("mcp.cancellationGraceMs", 100);

    deploy(vertx, config)
        .compose(server -> post(vertx, server.actualPort(),
            modernRequest("tools/call", "cancel-1", params)))
        .compose(Response::json)
        .onSuccess(json -> ctx.verify(() -> {
          assertEquals("Tool execution timed out", json.getJsonObject("result")
              .getJsonArray("content").getJsonObject(0).getString("text"));
          assertTrue(contextCancelled.get());
          assertTrue(providerCancelled.get());
          ctx.completeNow();
        }))
        .onFailure(ctx::failNow);
  }

  @Test
  void cancellation_failures_do_not_replace_the_timeout_result(Vertx vertx,
                                                                VertxTestContext ctx) {
    Tool cancellable = new Tool() {
      @Override public String name() { return "test.cancel.failure"; }
      @Override public ToolInvocation invokeManaged(JsonObject args, ToolContext context) {
        return new ToolInvocation(Promise.<ToolResult>promise().future(),
            () -> Future.failedFuture("cancellation unavailable"));
      }
    };
    tools = ToolRegistry.of(cancellable);
    JsonObject params = new JsonObject().put("name", "test.cancel.failure")
        .put("arguments", new JsonObject());

    deploy(vertx, new JsonObject().put("mcp.toolTimeoutMs", 10)
            .put("mcp.cancellationGraceMs", 100))
        .compose(server -> post(vertx, server.actualPort(),
            modernRequest("tools/call", "cancel-failure", params)))
        .compose(Response::json)
        .onSuccess(json -> ctx.verify(() -> {
          assertEquals("Tool execution timed out", json.getJsonObject("result")
              .getJsonArray("content").getJsonObject(0).getString("text"));
          ctx.completeNow();
        }))
        .onFailure(ctx::failNow);
  }

  @Test
  void enforces_limit_on_final_serialized_response(Vertx vertx, VertxTestContext ctx) {
    Tool large = new Tool() {
      @Override public String name() { return "test.large"; }
      @Override public Future<JsonObject> invoke(JsonObject args, ToolContext context) {
        return Future.succeededFuture(new JsonObject().put("value", "x".repeat(2_000)));
      }
    };
    tools = ToolRegistry.of(large);
    JsonObject params = new JsonObject().put("name", "test.large")
        .put("arguments", new JsonObject());

    deploy(vertx, new JsonObject().put("mcp.maxResponseBytes", 512))
        .compose(server -> post(vertx, server.actualPort(),
            modernRequest("tools/call", "large-1", params)))
        .compose(response -> expectJson(response, 500))
        .onSuccess(json -> ctx.verify(() -> {
          assertEquals(-32603, json.getJsonObject("error").getInteger("code"));
          assertTrue(json.getJsonObject("error").getString("message").contains("size limit"));
          ctx.completeNow();
        }))
        .onFailure(ctx::failNow);
  }

  @Test
  void validates_structured_output_against_advertised_schema(Vertx vertx,
                                                              VertxTestContext ctx) {
    Tool invalidOutput = new Tool() {
      @Override public String name() { return "test.output"; }
      @Override public ToolDefinition definition() {
        return ToolDefinition.builder(name())
            .outputSchema(new JsonObject().put("type", "object")
                .put("properties", new JsonObject().put("status",
                    new JsonObject().put("type", "string")))
                .put("required", new JsonArray().add("status")))
            .build();
      }
      @Override public Future<JsonObject> invoke(JsonObject args, ToolContext context) {
        return Future.succeededFuture(new JsonObject().put("status", 42));
      }
    };
    tools = ToolRegistry.of(invalidOutput);
    JsonObject params = new JsonObject().put("name", "test.output")
        .put("arguments", new JsonObject());

    deployAndPost(vertx, modernRequest("tools/call", "output-1", params))
        .compose(Response::json)
        .onSuccess(json -> ctx.verify(() -> {
          assertTrue(json.getJsonObject("result").getBoolean("isError"));
          assertEquals("Tool execution failed", json.getJsonObject("result")
              .getJsonArray("content").getJsonObject(0).getString("text"));
          ctx.completeNow();
        }))
        .onFailure(ctx::failNow);
  }

  @Test
  void input_required_results_enforce_client_capabilities(Vertx vertx,
                                                          VertxTestContext ctx) {
    Tool interactive = new Tool() {
      @Override public String name() { return "test.interactive"; }
      @Override public ToolInvocation invokeManaged(JsonObject args, ToolContext context) {
        ToolResult result = new InputRequiredToolResult(
            new JsonObject().put("elicitation/create", new JsonObject()
                .put("message", "Continue?")), "state-1", new JsonObject());
        return ToolInvocation.of(Future.succeededFuture(result));
      }
    };
    tools = ToolRegistry.of(interactive);
    JsonObject params = new JsonObject().put("name", "test.interactive")
        .put("arguments", new JsonObject());

    deployAndPost(vertx, modernRequest("tools/call", "input-1", params))
        .compose(response -> expectJson(response, 400))
        .onSuccess(json -> ctx.verify(() -> {
          assertEquals(-32021, json.getJsonObject("error").getInteger("code"));
          assertEquals("elicitation", json.getJsonObject("error").getJsonObject("data")
              .getJsonArray("requiredCapabilities").getString(0));
          ctx.completeNow();
        }))
        .onFailure(ctx::failNow);
  }

  @Test
  void input_required_results_render_for_capable_clients(Vertx vertx,
                                                         VertxTestContext ctx) {
    Tool interactive = new Tool() {
      @Override public String name() { return "test.interactive"; }
      @Override public ToolInvocation invokeManaged(JsonObject args, ToolContext context) {
        return ToolInvocation.of(Future.succeededFuture(new InputRequiredToolResult(
            new JsonObject().put("elicitation/create", new JsonObject()
                .put("message", "Continue?")), "state-2",
            new JsonObject().put("provider", "test"))));
      }
    };
    tools = ToolRegistry.of(interactive);
    JsonObject params = new JsonObject().put("name", "test.interactive")
        .put("arguments", new JsonObject());
    JsonObject request = modernRequest("tools/call", "input-2", params);
    request.getJsonObject("params").getJsonObject("_meta")
        .getJsonObject("io.modelcontextprotocol/clientCapabilities")
        .put("elicitation", new JsonObject());

    deployAndPost(vertx, request)
        .compose(Response::json)
        .onSuccess(json -> ctx.verify(() -> {
          JsonObject result = json.getJsonObject("result");
          assertEquals("input_required", result.getString("resultType"));
          assertEquals("state-2", result.getString("requestState"));
          assertEquals("test", result.getJsonObject("_meta").getString("provider"));
          assertNotNull(result.getJsonObject("_meta")
              .getJsonObject("io.modelcontextprotocol/serverInfo"));
          ctx.completeNow();
        }))
        .onFailure(ctx::failNow);
  }

  @Test
  void supports_boolean_integer_and_nested_parameter_headers(Vertx vertx,
                                                              VertxTestContext ctx) {
    Tool custom = new Tool() {
      @Override public String name() { return "custom.typed"; }
      @Override public JsonObject schema() {
        return new JsonObject().put("type", "object")
            .put("properties", new JsonObject()
                .put("enabled", new JsonObject().put("type", "boolean")
                    .put("x-mcp-header", "Enabled"))
                .put("count", new JsonObject().put("type", "integer")
                    .put("x-mcp-header", "Count"))
                .put("scope", new JsonObject().put("type", "object")
                    .put("properties", new JsonObject().put("region",
                        new JsonObject().put("type", "string")
                            .put("x-mcp-header", "Region")))));
      }
      @Override public Future<JsonObject> invoke(JsonObject args, ToolContext context) {
        return Future.succeededFuture(args);
      }
    };
    tools = ToolRegistry.of(custom);
    JsonObject arguments = new JsonObject().put("enabled", true).put("count", 42)
        .put("scope", new JsonObject().put("region", "eu-west-1"));
    JsonObject request = modernRequest("tools/call", "typed-1",
        new JsonObject().put("name", "custom.typed").put("arguments", arguments));
    JsonObject headers = baseHeaders("tools/call").put("Mcp-Name", "custom.typed")
        .put("Mcp-Param-Enabled", "true").put("Mcp-Param-Count", "42")
        .put("Mcp-Param-Region", "eu-west-1");

    deploy(vertx, new JsonObject())
        .compose(server -> postRaw(vertx, server.actualPort(), request.encode(), headers))
        .compose(Response::json)
        .onSuccess(json -> ctx.verify(() -> {
          assertFalse(json.getJsonObject("result").getBoolean("isError"));
          ctx.completeNow();
        }))
        .onFailure(ctx::failNow);
  }

  @Test
  void rejects_missing_and_mismatched_parameter_headers(Vertx vertx,
                                                        VertxTestContext ctx) {
    Tool custom = headerTool();
    tools = ToolRegistry.of(custom);
    JsonObject request = modernRequest("tools/call", "header-error",
        new JsonObject().put("name", "custom.header")
            .put("arguments", new JsonObject().put("region", "eu-west-1")));
    JsonObject missing = baseHeaders("tools/call").put("Mcp-Name", "custom.header");

    deploy(vertx, new JsonObject())
        .compose(server -> postRaw(vertx, server.actualPort(), request.encode(), missing)
            .compose(first -> expectJson(first, 400))
            .compose(first -> {
              assertEquals(-32020, first.getJsonObject("error").getInteger("code"));
              JsonObject mismatch = missing.copy().put("Mcp-Param-Region", "us-east-1");
              return postRaw(vertx, server.actualPort(), request.encode(), mismatch);
            }))
        .compose(response -> expectJson(response, 400))
        .onSuccess(json -> ctx.verify(() -> {
          assertEquals(-32020, json.getJsonObject("error").getInteger("code"));
          ctx.completeNow();
        }))
        .onFailure(ctx::failNow);
  }

  @Test
  void safe_tool_failures_are_disclosed_but_provider_defects_are_not(Vertx vertx,
                                                                     VertxTestContext ctx) {
    Tool safe = new Tool() {
      @Override public String name() { return "test.safe"; }
      @Override public Future<JsonObject> invoke(JsonObject args, ToolContext context) {
        return Future.failedFuture(new ToolExecutionException(
            "temporarily_unavailable", "Please retry", true, null));
      }
    };
    Tool broken = new Tool() {
      @Override public String name() { return "test.broken"; }
      @Override public ToolInvocation invokeManaged(JsonObject args, ToolContext context) {
        return null;
      }
    };
    tools = ToolRegistry.of(safe, broken);
    JsonObject safeRequest = modernRequest("tools/call", "safe-1",
        new JsonObject().put("name", "test.safe").put("arguments", new JsonObject()));
    JsonObject brokenRequest = modernRequest("tools/call", "broken-1",
        new JsonObject().put("name", "test.broken").put("arguments", new JsonObject()));

    deploy(vertx, new JsonObject())
        .compose(server -> post(vertx, server.actualPort(), safeRequest)
            .compose(Response::json)
            .compose(first -> {
              JsonObject result = first.getJsonObject("result");
              assertEquals("Please retry", result.getJsonArray("content")
                  .getJsonObject(0).getString("text"));
              assertEquals("temporarily_unavailable",
                  result.getJsonObject("_meta").getString("errorType"));
              assertTrue(result.getJsonObject("_meta").getBoolean("retryable"));
              return post(vertx, server.actualPort(), brokenRequest);
            }))
        .compose(Response::json)
        .onSuccess(json -> ctx.verify(() -> {
          assertEquals("Tool execution failed", json.getJsonObject("result")
              .getJsonArray("content").getJsonObject(0).getString("text"));
          ctx.completeNow();
        }))
        .onFailure(ctx::failNow);
  }

  @Test
  void bounds_concurrent_provider_execution_without_queueing(Vertx vertx,
                                                              VertxTestContext ctx) {
    Promise<Void> started = Promise.promise();
    Promise<ToolResult> providerResult = Promise.promise();
    Tool busy = new Tool() {
      @Override public String name() { return "test.busy"; }
      @Override public ToolInvocation invokeManaged(JsonObject args, ToolContext context) {
        started.tryComplete();
        return new ToolInvocation(providerResult.future(), () -> {
          providerResult.tryFail("cancelled");
          return Future.succeededFuture();
        });
      }
    };
    tools = ToolRegistry.of(busy);
    JsonObject params = new JsonObject().put("name", "test.busy")
        .put("arguments", new JsonObject());
    JsonObject config = new JsonObject()
        .put("mcp.maxConcurrentToolCalls", 1)
        .put("mcp.maxConcurrentCallsPerTool", 1)
        .put("mcp.toolTimeoutMs", 100);

    deploy(vertx, config)
        .compose(server -> {
          Future<Response> first = post(vertx, server.actualPort(),
              modernRequest("tools/call", "busy-1", params));
          return started.future()
              .compose(ignored -> post(vertx, server.actualPort(),
                  modernRequest("tools/call", "busy-2", params)))
              .compose(Response::json)
              .compose(second -> {
                assertEquals("Tool concurrency limit exceeded; retry later",
                    second.getJsonObject("result").getJsonArray("content")
                        .getJsonObject(0).getString("text"));
                return first.compose(Response::json);
              });
        })
        .onSuccess(first -> ctx.verify(() -> {
          assertEquals("Tool execution timed out", first.getJsonObject("result")
              .getJsonArray("content").getJsonObject(0).getString("text"));
          ctx.completeNow();
        }))
        .onFailure(ctx::failNow);
  }

  @Test
  void exposes_optional_readiness_metrics(Vertx vertx, VertxTestContext ctx) {
    deploy(vertx, new JsonObject().put("mcp.healthEnabled", true))
        .compose(server -> httpClient
            .request(HttpMethod.GET, server.actualPort(), "127.0.0.1", "/health/live")
            .compose(request -> request.send())
            .map(Response::new)
            .compose(response -> expectJson(response, 200))
            .compose(live -> {
              assertEquals("UP", live.getString("status"));
              return httpClient.request(HttpMethod.GET, server.actualPort(),
                  "127.0.0.1", "/health/ready");
            }))
        .compose(request -> request.send())
        .map(Response::new)
        .compose(response -> expectJson(response, 200))
        .onSuccess(json -> ctx.verify(() -> {
          assertEquals("UP", json.getString("status"));
          assertTrue(json.containsKey("activeToolCalls"));
          assertTrue(json.containsKey("toolErrors"));
          ctx.completeNow();
        }))
        .onFailure(ctx::failNow);
  }

  @Test
  void handles_cors_preflight_for_standard_and_tool_headers(Vertx vertx,
                                                            VertxTestContext ctx) {
    deploy(vertx, new JsonObject())
        .compose(server -> httpClient.request(HttpMethod.OPTIONS, server.actualPort(),
            "127.0.0.1", "/mcp")
            .compose(request -> request
                .putHeader("Access-Control-Request-Headers",
                    "Content-Type, MCP-Protocol-Version, Mcp-Param-Region")
                .send())
            .map(Response::new)
            .compose(first -> {
              assertEquals(204, first.status());
              assertEquals("Content-Type, MCP-Protocol-Version, Mcp-Param-Region",
                  first.header("Access-Control-Allow-Headers"));
              return httpClient.request(HttpMethod.OPTIONS, server.actualPort(),
                  "127.0.0.1", "/mcp");
            }))
        .compose(request -> request.putHeader("Access-Control-Request-Headers", "X-Unsafe").send())
        .map(Response::new)
        .compose(response -> expectJson(response, 400))
        .onSuccess(json -> ctx.verify(() -> {
          assertEquals(-32600, json.getJsonObject("error").getInteger("code"));
          ctx.completeNow();
        }))
        .onFailure(ctx::failNow);
  }

  @Test
  void reports_oversized_request_bodies_as_413(Vertx vertx, VertxTestContext ctx) {
    JsonObject request = modernRequest("server/discover", "large-body", new JsonObject()
        .put("padding", "x".repeat(1_000)));
    deploy(vertx, new JsonObject().put("mcp.maxBodyBytes", 256))
        .compose(server -> post(vertx, server.actualPort(), request))
        .compose(response -> expectJson(response, 413))
        .onSuccess(json -> ctx.verify(() -> {
          assertEquals(-32600, json.getJsonObject("error").getInteger("code"));
          assertTrue(json.getJsonObject("error").getString("message").contains("body"));
          ctx.completeNow();
        }))
        .onFailure(ctx::failNow);
  }

  @Test
  void trusts_forwarded_addresses_only_from_configured_proxies(Vertx vertx,
                                                               VertxTestContext ctx) {
    Tool address = new Tool() {
      @Override public String name() { return "test.address"; }
      @Override public Future<JsonObject> invoke(JsonObject args, ToolContext context) {
        return Future.succeededFuture(new JsonObject()
            .put("remoteAddress", context.metadata().getString("remoteAddress")));
      }
    };
    tools = ToolRegistry.of(address);
    JsonObject request = modernRequest("tools/call", "address-1",
        new JsonObject().put("name", "test.address").put("arguments", new JsonObject()));
    JsonObject headers = baseHeaders("tools/call").put("Mcp-Name", "test.address")
        .put("X-Forwarded-For", "203.0.113.7, 127.0.0.1");

    deploy(vertx, new JsonObject().put("mcp.trustedProxies", "127.0.0.1"))
        .compose(server -> postRaw(vertx, server.actualPort(), request.encode(), headers))
        .compose(Response::json)
        .onSuccess(json -> ctx.verify(() -> {
          assertEquals("203.0.113.7", json.getJsonObject("result")
              .getJsonObject("structuredContent").getString("remoteAddress"));
          ctx.completeNow();
        }))
        .onFailure(ctx::failNow);
  }

  @Test
  void bounds_oversized_jsonrpc_errors(Vertx vertx, VertxTestContext ctx) {
    String method = "extension/" + "x".repeat(1_000);
    JsonObject request = modernRequest(method, "large-error", new JsonObject());
    deploy(vertx, new JsonObject().put("mcp.maxResponseBytes", 512))
        .compose(server -> postRaw(vertx, server.actualPort(), request.encode(),
            baseHeaders(method)))
        .compose(response -> expectJson(response, 500))
        .onSuccess(json -> ctx.verify(() -> {
          assertEquals("Internal server error",
              json.getJsonObject("error").getString("message"));
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
    JsonObject request = modernRequest("server/discover", 15, new JsonObject());
    JsonObject headers = baseHeaders("server/discover").put("Origin", "https://client.example");
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
    JsonObject request = modernRequest("server/discover", 16, new JsonObject());
    deploy(vertx, config)
        .compose(server -> postRaw(vertx, server.actualPort(), request.encode(), baseHeaders("server/discover"))
            .compose(first -> {
              assertEquals(401, first.status());
              JsonObject authorized = baseHeaders("server/discover").put("Authorization", "Bearer secret");
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
    JsonObject request = modernRequest("server/discover", 17, new JsonObject());
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
    JsonObject request = modernRequest("server/discover", 19, new JsonObject());
    deploy(vertx, config)
        .compose(server -> postRaw(vertx, server.actualPort(), "/api/mcp",
            request.encode(), baseHeaders("server/discover")))
        .onSuccess(response -> ctx.verify(() -> {
          assertEquals(200, response.status());
          ctx.completeNow();
        }))
        .onFailure(ctx::failNow);
  }

  private Future<McpServerVerticle> deploy(Vertx vertx, JsonObject config) {
    McpServerVerticle server = new McpServerVerticle(tools, "resourceId");
    JsonObject deploymentConfig = config.copy().put("mcp.port", 0);
    LOG.atDebug().log(() -> "Deploying MCP integration-test server: tools=" + tools.size()
        + " basePath=\"" + deploymentConfig.getString("mcp.basePath", "") + "\""
        + " healthEnabled=" + deploymentConfig.getBoolean("mcp.healthEnabled", false));
    return vertx.deployVerticle(server, new DeploymentOptions().setConfig(deploymentConfig))
        .map(ignored -> {
          LOG.atDebug().log(() -> "MCP integration-test server deployed: port=" + server.actualPort());
          return server;
        });
  }

  private Tool headerTool() {
    return new Tool() {
      @Override public String name() { return "custom.header"; }
      @Override public JsonObject schema() {
        return new JsonObject().put("type", "object")
            .put("properties", new JsonObject().put("region", new JsonObject()
                .put("type", "string").put("x-mcp-header", "Region")));
      }
      @Override public Future<JsonObject> invoke(JsonObject args, ToolContext context) {
        return Future.succeededFuture(new JsonObject());
      }
    };
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
    String rpcMethod = headers.getString("Mcp-Method", "unspecified");
    int bodyBytes = body.getBytes(StandardCharsets.UTF_8).length;
    LOG.atDebug().log(() -> "Sending MCP integration-test request: method=" + rpcMethod
        + " path=\"" + path + "\" port=" + port + " bodyBytes=" + bodyBytes
        + " headerNames=" + headers.fieldNames());
    return httpClient.request(HttpMethod.POST, port, "127.0.0.1", path)
        .compose(request -> {
          headers.forEach(entry -> request.putHeader(entry.getKey(), String.valueOf(entry.getValue())));
          return request.send(Buffer.buffer(body));
        })
        .map(response -> {
          LOG.atDebug().log(() -> "Received MCP integration-test response: method=" + rpcMethod
              + " status=" + response.statusCode());
          return new Response(response);
        });
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
    LOG.atDebug().log(() -> "Asserting MCP JSON response: expectedStatus=" + expectedStatus
        + " actualStatus=" + response.status());
    assertEquals(expectedStatus, response.status());
    return response.json()
        .onSuccess(json -> LOG.atDebug().log(() -> "Decoded MCP JSON response: topLevelFields="
            + json.fieldNames()));
  }

  private record Response(HttpClientResponse raw) {
    int status() { return raw.statusCode(); }
    String header(String name) { return raw.getHeader(name); }
    Future<JsonObject> json() { return raw.body().map(Buffer::toJsonObject); }
  }
}
