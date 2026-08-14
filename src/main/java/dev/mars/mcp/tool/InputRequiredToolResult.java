package dev.mars.mcp.tool;

import io.vertx.core.json.JsonObject;

import java.util.Map;

/** An MCP multi-round-trip result requesting additional client input. */
public record InputRequiredToolResult(
    JsonObject inputRequests,
    String requestState,
    JsonObject metadata) implements ToolResult {

  public InputRequiredToolResult {
    boolean hasRequests = inputRequests != null && !inputRequests.isEmpty();
    boolean hasState = requestState != null && !requestState.isBlank();
    if (!hasRequests && !hasState) {
      throw new IllegalArgumentException(
          "At least one of inputRequests or requestState must be present");
    }
    if (inputRequests != null) {
      inputRequests.forEach(entry -> {
        if (entry.getKey() == null || entry.getKey().isBlank()) {
          throw new IllegalArgumentException("MCP input request IDs must not be blank");
        }
        if (!(entry.getValue() instanceof JsonObject request)) {
          throw new IllegalArgumentException(
              "MCP input request '" + entry.getKey() + "' must be an object");
        }
        McpInputRequest.fromJson(request);
      });
      inputRequests = inputRequests.copy();
    }
    metadata = metadata == null
        ? new JsonObject() : (JsonObject) McpJsonValues.copy(metadata);
    McpWireValidator.metadata(metadata);
  }

  /** Builds a result from typed requests keyed by server-assigned request IDs. */
  public static InputRequiredToolResult requests(Map<String, McpInputRequest> requests,
                                                 String requestState,
                                                 JsonObject metadata) {
    JsonObject values = new JsonObject();
    requests.forEach((id, request) -> values.put(id, request.toJson()));
    return new InputRequiredToolResult(values, requestState, metadata);
  }

  /** Builds a request-state-only result, for example when shedding work. */
  public static InputRequiredToolResult stateOnly(String requestState) {
    return new InputRequiredToolResult(null, requestState, new JsonObject());
  }

  @Override
  public JsonObject metadata() {
    return metadata.copy();
  }

  @Override
  public JsonObject inputRequests() {
    return inputRequests == null ? null : inputRequests.copy();
  }

  @Override
  public JsonObject toJson() {
    JsonObject result = new JsonObject().put("resultType", "input_required");
    if (inputRequests != null && !inputRequests.isEmpty()) {
      result.put("inputRequests", inputRequests.copy());
    }
    if (requestState != null && !requestState.isBlank()) {
      result.put("requestState", requestState);
    }
    if (!metadata.isEmpty()) {
      result.put("_meta", metadata.copy());
    }
    return result;
  }
}
