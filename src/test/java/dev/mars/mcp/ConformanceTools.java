package dev.mars.mcp;

import dev.mars.mcp.tool.CompleteToolResult;
import dev.mars.mcp.tool.ContentBlock;
import dev.mars.mcp.tool.InputRequiredToolResult;
import dev.mars.mcp.tool.McpInputRequest;
import dev.mars.mcp.tool.Tool;
import dev.mars.mcp.tool.ToolContext;
import dev.mars.mcp.tool.ToolInvocation;
import dev.mars.mcp.tool.ToolResult;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/** Purpose-built providers for official server scenarios; never packaged in the production JAR. */
final class ConformanceTools {

  private static final String PIXEL_PNG =
      "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9Z2S8AAAAASUVORK5CYII=";
  private static final String MINIMAL_WAV =
      "UklGRiQAAABXQVZFZm10IBAAAAABAAEAQB8AAEAfAAABAAgAZGF0YQAAAAA=";

  private ConformanceTools() {}

  static Tool[] all() {
    return new Tool[] {
        fixed("test_simple_text", CompleteToolResult.text(
            "This is a simple text response for testing.")),
        fixed("test_image_content", complete(ContentBlock.image(PIXEL_PNG, "image/png"))),
        fixed("test_audio_content", complete(ContentBlock.audio(MINIMAL_WAV, "audio/wav"))),
        fixed("test_embedded_resource", complete(embeddedResource(
            "test://embedded-resource", "text/plain",
            "This is an embedded resource content."))),
        fixed("test_multiple_content_types", new CompleteToolResult(List.of(
            ContentBlock.text("Multiple content types test:"),
            ContentBlock.image(PIXEL_PNG, "image/png"),
            embeddedResource("test://mixed-content-resource", "application/json",
                "{\"test\":\"data\",\"value\":123}")), null, false, new JsonObject())),
        fixed("test_error_handling", new CompleteToolResult(
            List.of(ContentBlock.text("This tool intentionally returns an error for testing")),
            null, true, new JsonObject())),
        managed("test_input_required_result_elicitation", ConformanceTools::elicitation),
        managed("test_input_required_result_sampling", ConformanceTools::sampling),
        managed("test_input_required_result_list_roots", ConformanceTools::roots),
        managed("test_input_required_result_request_state", ConformanceTools::requestState),
        managed("test_input_required_result_multiple_inputs", ConformanceTools::multipleInputs),
        managed("test_input_required_result_multi_round", ConformanceTools::multiRound),
        managed("test_input_required_result_capabilities", ConformanceTools::capabilities)
    };
  }

  private static ToolResult elicitation(JsonObject ignored, ToolContext context) {
    if (hasResponse(context, "user_name")) return CompleteToolResult.text("Hello, Alice!");
    return InputRequiredToolResult.requests(Map.of("user_name", elicitationRequest(
        "What is your name?", "name", "string")), null, null);
  }

  private static ToolResult sampling(JsonObject ignored, ToolContext context) {
    if (hasResponse(context, "capital_question")) {
      return CompleteToolResult.text("The capital of France is Paris.");
    }
    return InputRequiredToolResult.requests(Map.of("capital_question", samplingRequest(
        "What is the capital of France?", 100)), null, null);
  }

  private static ToolResult roots(JsonObject ignored, ToolContext context) {
    if (hasResponse(context, "client_roots")) return CompleteToolResult.text("Roots received.");
    return InputRequiredToolResult.requests(Map.of(
        "client_roots", new McpInputRequest("roots/list", new JsonObject())), null, null);
  }

  private static ToolResult requestState(JsonObject ignored, ToolContext context) {
    if (hasResponse(context, "confirm") && "fixture-state".equals(requestState(context))) {
      return CompleteToolResult.text("state-ok");
    }
    return InputRequiredToolResult.requests(Map.of("confirm", elicitationRequest(
        "Please confirm", "ok", "boolean")), "fixture-state", null);
  }

  private static ToolResult multipleInputs(JsonObject ignored, ToolContext context) {
    JsonObject responses = inputResponses(context);
    if (responses != null && responses.containsKey("user_name")
        && responses.containsKey("greeting") && responses.containsKey("client_roots")
        && "multiple-state".equals(requestState(context))) {
      return CompleteToolResult.text("All inputs received.");
    }
    return InputRequiredToolResult.requests(Map.of(
        "user_name", elicitationRequest("What is your name?", "name", "string"),
        "greeting", samplingRequest("Generate a greeting", 50),
        "client_roots", new McpInputRequest("roots/list", new JsonObject())),
        "multiple-state", null);
  }

  private static ToolResult multiRound(JsonObject ignored, ToolContext context) {
    String state = requestState(context);
    if ("round-2".equals(state) && hasResponse(context, "step2")) {
      return CompleteToolResult.text("Multi-round inputs complete.");
    }
    if ("round-1".equals(state) && hasResponse(context, "step1")) {
      return InputRequiredToolResult.requests(Map.of("step2", elicitationRequest(
          "Step 2: What is your favorite color?", "color", "string")), "round-2", null);
    }
    return InputRequiredToolResult.requests(Map.of("step1", elicitationRequest(
        "Step 1: What is your name?", "name", "string")), "round-1", null);
  }

  private static ToolResult capabilities(JsonObject ignored, ToolContext context) {
    return InputRequiredToolResult.requests(Map.of("sampling_only", samplingRequest(
        "Return a short test response", 20)), null, null);
  }

  private static Tool fixed(String name, CompleteToolResult result) {
    return managed(name, (ignored, context) -> result);
  }

  private static Tool managed(String name,
                              BiFunction<JsonObject, ToolContext, ToolResult> invocation) {
    return new Tool() {
      @Override public String name() { return name; }
      @Override public String description() { return "Official MCP conformance fixture: " + name; }
      @Override public ToolInvocation invokeManaged(JsonObject arguments, ToolContext context) {
        return ToolInvocation.of(Future.succeededFuture(invocation.apply(arguments, context)));
      }
    };
  }

  private static CompleteToolResult complete(ContentBlock block) {
    return new CompleteToolResult(List.of(block), null, false, new JsonObject());
  }

  private static ContentBlock embeddedResource(String uri, String mimeType, String text) {
    return ContentBlock.raw(new JsonObject().put("type", "resource")
        .put("resource", new JsonObject().put("uri", uri)
            .put("mimeType", mimeType).put("text", text)));
  }

  private static McpInputRequest elicitationRequest(String message, String field, String type) {
    JsonObject requestedSchema = new JsonObject().put("type", "object")
        .put("properties", new JsonObject().put(field, new JsonObject().put("type", type)))
        .put("required", new JsonArray().add(field));
    return new McpInputRequest("elicitation/create", new JsonObject()
        .put("message", message).put("requestedSchema", requestedSchema));
  }

  private static McpInputRequest samplingRequest(String text, int maxTokens) {
    JsonObject content = new JsonObject().put("type", "text").put("text", text);
    JsonObject message = new JsonObject().put("role", "user").put("content", content);
    return new McpInputRequest("sampling/createMessage", new JsonObject()
        .put("messages", new JsonArray().add(message)).put("maxTokens", maxTokens));
  }

  private static boolean hasResponse(ToolContext context, String id) {
    JsonObject responses = inputResponses(context);
    return responses != null && responses.containsKey(id);
  }

  private static JsonObject inputResponses(ToolContext context) {
    return context.metadata().getJsonObject("inputResponses");
  }

  private static String requestState(ToolContext context) {
    return context.metadata().getString("requestState");
  }
}
