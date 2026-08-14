package dev.mars.mcp.tool;

import io.vertx.core.json.JsonObject;

import java.util.Set;

/** A server-initiated request carried inside an MCP multi-round-trip result. */
public record McpInputRequest(String method, JsonObject params) {

  private static final Set<String> SUPPORTED_METHODS = Set.of(
      "elicitation/create", "sampling/createMessage", "roots/list");

  public McpInputRequest {
    if (method == null || !SUPPORTED_METHODS.contains(method)) {
      throw new IllegalArgumentException("Unsupported MCP input request method: " + method);
    }
    if (params == null) {
      throw new IllegalArgumentException("MCP input request params must be an object");
    }
    params = params.copy();
  }

  @Override
  public JsonObject params() {
    return params.copy();
  }

  public JsonObject toJson() {
    return new JsonObject().put("method", method).put("params", params.copy());
  }

  public static McpInputRequest fromJson(JsonObject value) {
    if (value == null || !(value.getValue("method") instanceof String method)
        || !(value.getValue("params") instanceof JsonObject params)) {
      throw new IllegalArgumentException(
          "MCP input request values must contain string method and object params");
    }
    return new McpInputRequest(method, params);
  }
}
