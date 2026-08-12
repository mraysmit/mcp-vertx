package dev.mars.mcp.tool;

import io.vertx.core.json.JsonObject;

/** An MCP multi-round-trip result requesting additional client input. */
public record InputRequiredToolResult(
    JsonObject inputRequests,
    String requestState,
    JsonObject metadata) implements ToolResult {

  public InputRequiredToolResult {
    if (inputRequests == null || inputRequests.isEmpty()) {
      throw new IllegalArgumentException("inputRequests must not be empty");
    }
    inputRequests = inputRequests.copy();
    metadata = metadata == null ? new JsonObject() : metadata.copy();
  }

  @Override
  public JsonObject metadata() {
    return metadata.copy();
  }

  @Override
  public JsonObject inputRequests() {
    return inputRequests.copy();
  }

  @Override
  public JsonObject toJson() {
    JsonObject result = new JsonObject()
        .put("resultType", "input_required")
        .put("inputRequests", inputRequests.copy());
    if (requestState != null && !requestState.isBlank()) {
      result.put("requestState", requestState);
    }
    if (!metadata.isEmpty()) {
      result.put("_meta", metadata.copy());
    }
    return result;
  }
}
