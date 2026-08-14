package dev.mars.mcp.tool;

import dev.mars.mcp.testing.TestLoggingExtension;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(TestLoggingExtension.class)
class ToolApiTest {

  @Test
  void content_blocks_render_all_supported_types_and_own_their_data() {
    assertEquals("hello", ContentBlock.text("hello").toJson().getString("text"));
    assertEquals("image/png", ContentBlock.image("aW1hZ2U=", "image/png")
        .toJson().getString("mimeType"));
    assertEquals("audio/mpeg", ContentBlock.audio("YXVkaW8=", "audio/mpeg")
        .toJson().getString("mimeType"));

    JsonObject link = ContentBlock.resourceLink("docs", "https://example.test/docs",
        "Documentation", "text/html").toJson();
    assertEquals("resource_link", link.getString("type"));
    assertEquals("Documentation", link.getString("description"));
    assertFalse(ContentBlock.resourceLink("docs", "https://example.test", null, null)
        .toJson().containsKey("mimeType"));

    JsonObject source = new JsonObject().put("type", "custom").put("value", 1);
    ContentBlock raw = ContentBlock.raw(source);
    source.put("value", 2);
    JsonObject rendered = raw.toJson();
    assertEquals(1, rendered.getInteger("value"));
    rendered.put("value", 3);
    assertEquals(1, raw.toJson().getInteger("value"));

    assertThrows(NullPointerException.class, () -> ContentBlock.text(null));
    assertThrows(IllegalArgumentException.class, () -> ContentBlock.image("", "image/png"));
    assertThrows(IllegalArgumentException.class, () -> ContentBlock.audio("data", " "));
    assertThrows(IllegalArgumentException.class,
        () -> ContentBlock.resourceLink("", "https://example.test", null, null));
    assertThrows(NullPointerException.class, () -> ContentBlock.raw(null));
  }

  @Test
  void complete_results_render_optional_fields_and_defensively_copy() {
    JsonObject structured = new JsonObject().put("answer", 42);
    JsonObject metadata = new JsonObject().put("trace", "abc");
    CompleteToolResult result = new CompleteToolResult(
        List.of(ContentBlock.text("done")), structured, false, metadata);
    structured.put("answer", 0);
    metadata.put("trace", "changed");

    JsonObject json = result.toJson();
    assertEquals(42, json.getJsonObject("structuredContent").getInteger("answer"));
    assertEquals("abc", json.getJsonObject("_meta").getString("trace"));
    assertEquals("done", json.getJsonArray("content").getJsonObject(0).getString("text"));

    assertFalse(CompleteToolResult.text("plain").toJson().containsKey("structuredContent"));
    assertTrue(new CompleteToolResult(null, null, true, null).toJson()
        .getJsonArray("content").isEmpty());
    assertEquals(42, CompleteToolResult.structured(new JsonObject().put("answer", 42))
        .structuredContent().getInteger("answer"));
    CompleteToolResult arrayResult = CompleteToolResult.structured(
        new JsonArray().add(new JsonObject().put("answer", 42)));
    assertEquals(42, ((JsonArray) arrayResult.structuredContentValue())
        .getJsonObject(0).getInteger("answer"));
    assertTrue(CompleteToolResult.structuredNull().toJson().containsKey("structuredContent"));
    assertNull(CompleteToolResult.structuredNull().toJson().getValue("structuredContent"));
    assertThrows(IllegalArgumentException.class, () -> CompleteToolResult.structured(null));
    assertThrows(IllegalArgumentException.class,
        () -> CompleteToolResult.structured(Double.NaN));
  }

  @Test
  void input_required_results_validate_and_render_optional_state() {
    JsonObject requests = new JsonObject().put("confirm",
        new McpInputRequest("elicitation/create", new JsonObject()
            .put("mode", "form").put("message", "Continue?")
            .put("requestedSchema", new JsonObject().put("type", "object"))).toJson());
    InputRequiredToolResult result = new InputRequiredToolResult(
        requests, "state-1", new JsonObject().put("trace", true));
    requests.clear();

    JsonObject json = result.toJson();
    assertEquals("input_required", json.getString("resultType"));
    assertEquals("state-1", json.getString("requestState"));
    assertTrue(json.getJsonObject("_meta").getBoolean("trace"));
    assertFalse(new InputRequiredToolResult(
        new JsonObject().put("roots",
            new McpInputRequest("roots/list", new JsonObject()).toJson()), " ", null)
        .toJson().containsKey("requestState"));
    assertEquals("shed-1", InputRequiredToolResult.stateOnly("shed-1")
        .toJson().getString("requestState"));
    assertFalse(InputRequiredToolResult.stateOnly("shed-1").toJson()
        .containsKey("inputRequests"));
    assertThrows(IllegalArgumentException.class,
        () -> new InputRequiredToolResult(null, null, null));
    assertThrows(IllegalArgumentException.class,
        () -> new InputRequiredToolResult(new JsonObject(), null, null));
    assertThrows(IllegalArgumentException.class,
        () -> new InputRequiredToolResult(
            new JsonObject().put("elicitation/create", new JsonObject()), null, null));
  }

  @Test
  void json_values_and_input_requests_accept_the_complete_json_model() {
    for (Object scalar : List.of("text", true, (byte) 1, (short) 2, 3, 4L, 5.0F, 6.0D,
        BigInteger.TEN, BigDecimal.TEN)) {
      assertEquals(scalar, McpJsonValues.copy(scalar));
    }
    assertNull(McpJsonValues.copy(null));

    JsonObject nested = new JsonObject().put("items", new JsonArray().add(1));
    JsonObject objectCopy = (JsonObject) McpJsonValues.copy(nested);
    nested.getJsonArray("items").add(2);
    assertEquals(1, objectCopy.getJsonArray("items").size());

    Map<String, Object> map = new LinkedHashMap<>();
    map.put("list", List.of("a", Map.of("answer", 42)));
    JsonObject mapCopy = (JsonObject) McpJsonValues.copy(map);
    assertEquals(42, mapCopy.getJsonArray("list").getJsonObject(1).getInteger("answer"));
    Map<Object, Object> invalidMap = new LinkedHashMap<>();
    invalidMap.put(1, "value");
    assertThrows(IllegalArgumentException.class, () -> McpJsonValues.copy(invalidMap));
    assertThrows(IllegalArgumentException.class, () -> McpJsonValues.copy(new Object()));
    assertThrows(IllegalArgumentException.class, () -> McpJsonValues.copy(Float.NaN));
    assertThrows(IllegalArgumentException.class,
        () -> McpJsonValues.copy(Double.POSITIVE_INFINITY));

    JsonObject params = new JsonObject().put("prompt", "hello");
    McpInputRequest request = new McpInputRequest("sampling/createMessage", params);
    params.put("prompt", "changed");
    assertEquals("hello", request.params().getString("prompt"));
    assertEquals("sampling/createMessage", McpInputRequest.fromJson(request.toJson()).method());
    assertThrows(IllegalArgumentException.class, () -> new McpInputRequest(null, params));
    assertThrows(IllegalArgumentException.class,
        () -> new McpInputRequest("unsupported/request", params));
    assertThrows(IllegalArgumentException.class,
        () -> new McpInputRequest("roots/list", null));
    assertThrows(IllegalArgumentException.class, () -> McpInputRequest.fromJson(null));
    assertThrows(IllegalArgumentException.class,
        () -> McpInputRequest.fromJson(new JsonObject().put("method", 1)
            .put("params", new JsonObject())));
    assertThrows(IllegalArgumentException.class,
        () -> McpInputRequest.fromJson(new JsonObject().put("method", "roots/list")
            .put("params", "not-an-object")));

    InputRequiredToolResult typed = InputRequiredToolResult.requests(
        Map.of("sample-1", request), null, new JsonObject().put("trace", "one"));
    assertEquals("sampling/createMessage", typed.inputRequests()
        .getJsonObject("sample-1").getString("method"));
    assertEquals("one", typed.metadata().getString("trace"));
    assertThrows(IllegalArgumentException.class,
        () -> new InputRequiredToolResult(new JsonObject().put(" ", request.toJson()), null, null));
    assertThrows(IllegalArgumentException.class,
        () -> new InputRequiredToolResult(new JsonObject().put("request-1", "bad"), null, null));
  }

  @Test
  void provider_wire_values_are_validated_before_they_can_be_serialized() {
    JsonObject annotated = ContentBlock.raw(new JsonObject()
        .put("type", "resource")
        .put("resource", new JsonObject()
            .put("uri", "https://example.test/readme")
            .put("mimeType", "text/plain")
            .put("text", "hello"))
        .put("annotations", new JsonObject()
            .put("audience", new JsonArray().add("user").add("assistant"))
            .put("priority", 0.5)
            .put("lastModified", "2026-08-14T00:00:00Z"))).toJson();
    assertEquals("hello", annotated.getJsonObject("resource").getString("text"));
    assertEquals("blob", ContentBlock.raw(new JsonObject()
        .put("type", "resource")
        .put("resource", new JsonObject()
            .put("uri", "urn:test:blob").put("blob", "YmxvYg==")))
        .toJson().getJsonObject("resource").fieldNames().stream()
        .filter("blob"::equals).findFirst().orElseThrow());
    assertEquals("extension.example", ContentBlock.raw(new JsonObject()
        .put("type", "extension.example")).toJson().getString("type"));

    assertThrows(IllegalArgumentException.class,
        () -> ContentBlock.raw(new JsonObject().put("type", "text").put("text", 1)));
    assertThrows(IllegalArgumentException.class,
        () -> ContentBlock.raw(new JsonObject().put("type", "image")
            .put("data", " ").put("mimeType", "image/png")));
    assertThrows(IllegalArgumentException.class,
        () -> ContentBlock.raw(new JsonObject().put("type", "resource_link")
            .put("name", "docs").put("uri", "relative/path")));
    assertThrows(IllegalArgumentException.class,
        () -> ContentBlock.raw(new JsonObject().put("type", "resource_link")
            .put("name", "docs").put("uri", "https://bad uri")));
    assertThrows(IllegalArgumentException.class,
        () -> ContentBlock.raw(new JsonObject().put("type", "resource")));
    assertThrows(IllegalArgumentException.class,
        () -> ContentBlock.raw(new JsonObject().put("type", "resource")
            .put("resource", new JsonObject().put("uri", "urn:test:none"))));
    assertThrows(IllegalArgumentException.class,
        () -> ContentBlock.raw(new JsonObject().put("type", "resource")
            .put("resource", new JsonObject().put("uri", "urn:test:both")
                .put("text", "text").put("blob", "blob"))));
    assertThrows(IllegalArgumentException.class,
        () -> ContentBlock.raw(new JsonObject().put("type", "text").put("text", "ok")
            .put("annotations", "bad")));
    assertThrows(IllegalArgumentException.class,
        () -> ContentBlock.raw(new JsonObject().put("type", "text").put("text", "ok")
            .put("annotations", new JsonObject().put("audience", "user"))));
    assertThrows(IllegalArgumentException.class,
        () -> ContentBlock.raw(new JsonObject().put("type", "text").put("text", "ok")
            .put("annotations", new JsonObject().put("audience", new JsonArray().add("server")))));
    assertThrows(IllegalArgumentException.class,
        () -> ContentBlock.raw(new JsonObject().put("type", "text").put("text", "ok")
            .put("annotations", new JsonObject().put("priority", 2))));
    assertThrows(IllegalArgumentException.class,
        () -> ContentBlock.raw(new JsonObject().put("type", "text").put("text", "ok")
            .put("annotations", new JsonObject().put("lastModified", true))));

    JsonArray icons = new JsonArray()
        .add(new JsonObject().put("src", "data:image/png;base64,AA==")
            .put("mimeType", "image/png").put("theme", "dark")
            .put("sizes", new JsonArray().add("16x16").add("any")));
    JsonObject annotations = new JsonObject().put("title", "Safe")
        .put("readOnlyHint", true).put("destructiveHint", false)
        .put("idempotentHint", true).put("openWorldHint", false);
    assertEquals("dark", ToolDefinition.builder("validated").icons(icons)
        .annotations(annotations).build().icons().getJsonObject(0).getString("theme"));

    assertBadToolIcon(new JsonArray().add("not-an-object"));
    assertBadToolIcon(new JsonArray().add(new JsonObject().put("src", "https://bad uri")));
    assertBadToolIcon(new JsonArray().add(new JsonObject()
        .put("src", "https://example.test/icon").put("mimeType", true)));
    assertBadToolIcon(new JsonArray().add(new JsonObject()
        .put("src", "https://example.test/icon").put("theme", "system")));
    assertBadToolIcon(new JsonArray().add(new JsonObject()
        .put("src", "https://example.test/icon").put("sizes", "16x16")));
    assertBadToolIcon(new JsonArray().add(new JsonObject()
        .put("src", "https://example.test/icon").put("sizes", new JsonArray().add(" "))));
    assertThrows(IllegalArgumentException.class, () -> ToolDefinition.builder("bad.annotation")
        .annotations(new JsonObject().put("title", true)).build());
    assertThrows(IllegalArgumentException.class, () -> ToolDefinition.builder("bad.annotation")
        .annotations(new JsonObject().put("openWorldHint", "yes")).build());
    assertThrows(IllegalArgumentException.class, () -> new CompleteToolResult(
        List.of(ContentBlock.text("ok")), null, false,
        new JsonObject().put("bad\nkey", "value")));
    assertThrows(IllegalArgumentException.class, () -> new CompleteToolResult(
        List.of(ContentBlock.text("ok")), null, false,
        new JsonObject().put("bad-value", new Object())));
  }

  private static void assertBadToolIcon(JsonArray icons) {
    assertThrows(IllegalArgumentException.class,
        () -> ToolDefinition.builder("bad.icon").icons(icons).build());
  }

  @Test
  void tool_definitions_render_metadata_and_defensively_copy() {
    JsonObject input = new JsonObject().put("type", "object");
    JsonObject output = new JsonObject().put("type", "string");
    JsonArray icons = new JsonArray().add(
        new JsonObject().put("src", "https://example.test/icon.png"));
    ToolDefinition definition = ToolDefinition.builder("demo")
        .title("Demo")
        .description("A demonstration")
        .inputSchema(input)
        .outputSchema(output)
        .icons(icons)
        .annotations(new JsonObject().put("readOnlyHint", true))
        .execution(new JsonObject().put("taskSupport", "optional"))
        .build();
    input.put("type", "array");
    icons.clear();

    JsonObject json = definition.toJson();
    assertEquals("Demo", json.getString("title"));
    assertEquals("object", json.getJsonObject("inputSchema").getString("type"));
    assertEquals(1, json.getJsonArray("icons").size());
    assertEquals("string", definition.outputSchema().getString("type"));
    assertTrue(definition.annotations().getBoolean("readOnlyHint"));
    assertEquals("optional", definition.execution().getString("taskSupport"));

    ToolDefinition minimal = new ToolDefinition("minimal", " ", null,
        null, null, null, null, null);
    assertFalse(minimal.toJson().containsKey("title"));
    assertEquals("", minimal.description());
    assertThrows(IllegalArgumentException.class, () -> ToolDefinition.builder(" ").build());
    assertThrows(IllegalArgumentException.class, () -> ToolDefinition.builder("bad.icon")
        .icons(new JsonArray().add(new JsonObject().put("src", "file:///secret"))).build());
  }

  @Test
  void tool_context_exposes_deadline_cancellation_and_owned_metadata() {
    JsonObject metadata = new JsonObject().put("client", "test");
    ToolContext context = new ToolContext("correlation", "resource", metadata,
        System.currentTimeMillis() + 10_000);
    metadata.put("client", "changed");
    assertEquals("test", context.metadata().getString("client"));
    assertTrue(context.remainingTimeMillis() > 0);
    assertFalse(context.isCancelled());
    assertTrue(context.cancel());
    assertFalse(context.cancel());
    assertTrue(context.cancellation().succeeded());
    assertTrue(context.isCancelled());
    assertEquals(Long.MAX_VALUE, new ToolContext("c", "r", null).remainingTimeMillis());
    assertEquals(0, new ToolContext("c", "r", null, 1).remainingTimeMillis());
    assertThrows(IllegalArgumentException.class, () -> new ToolContext("", "r", null));
    assertThrows(IllegalArgumentException.class, () -> new ToolContext("c", "", null));
    assertThrows(IllegalArgumentException.class, () -> new ToolContext("c", "r", null, 0));
  }

  @Test
  void managed_invocations_and_safe_exceptions_cover_defensive_failures() {
    ToolInvocation successful = ToolInvocation.of(Future.succeededFuture(CompleteToolResult.text("ok")));
    assertTrue(successful.result().succeeded());
    assertTrue(successful.cancel().succeeded());
    assertTrue(new ToolInvocation(successful.result(), () -> null).cancel().failed());
    assertTrue(new ToolInvocation(successful.result(), () -> {
      throw new IllegalStateException("boom");
    }).cancel().failed());
    assertThrows(NullPointerException.class, () -> new ToolInvocation(null, Future::succeededFuture));

    ToolExecutionException simple = new ToolExecutionException("safe");
    assertEquals("tool_execution_failed", simple.errorType());
    assertFalse(simple.retryable());
    ToolExecutionException retryable = new ToolExecutionException(
        "busy", "Retry", true, new RuntimeException("private"));
    assertTrue(retryable.retryable());
    assertThrows(IllegalArgumentException.class,
        () -> new ToolExecutionException("", "safe", false, null));
    assertThrows(IllegalArgumentException.class,
        () -> new ToolExecutionException("type", "", false, null));
  }

  @Test
  void default_tool_contract_is_explicit() {
    Tool tool = () -> "default.tool";
    ToolContext context = new ToolContext("c", "r", null);
    assertTrue(tool.invoke(new JsonObject(), context).failed());
    assertTrue(tool.invokeManaged(new JsonObject(), context).result().failed());
    assertEquals("object", tool.definition().inputSchema().getString("type"));
  }
}
