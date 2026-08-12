package dev.mars.mcp.tool;

import dev.mars.mcp.testing.TestLoggingExtension;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;

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
    assertThrows(IllegalArgumentException.class, () -> CompleteToolResult.structured(null));
  }

  @Test
  void input_required_results_validate_and_render_optional_state() {
    JsonObject requests = new JsonObject().put("elicitation/create", new JsonObject());
    InputRequiredToolResult result = new InputRequiredToolResult(
        requests, "state-1", new JsonObject().put("trace", true));
    requests.clear();

    JsonObject json = result.toJson();
    assertEquals("input_required", json.getString("resultType"));
    assertEquals("state-1", json.getString("requestState"));
    assertTrue(json.getJsonObject("_meta").getBoolean("trace"));
    assertFalse(new InputRequiredToolResult(
        new JsonObject().put("roots/list", new JsonObject()), " ", null)
        .toJson().containsKey("requestState"));
    assertThrows(IllegalArgumentException.class,
        () -> new InputRequiredToolResult(null, null, null));
    assertThrows(IllegalArgumentException.class,
        () -> new InputRequiredToolResult(new JsonObject(), null, null));
  }

  @Test
  void tool_definitions_render_metadata_and_defensively_copy() {
    JsonObject input = new JsonObject().put("type", "object");
    JsonObject output = new JsonObject().put("type", "string");
    JsonArray icons = new JsonArray().add(new JsonObject().put("src", "icon.png"));
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
