package dev.mars.mcp.tool;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

/**
 * A capability exposed by the MCP server through {@code tools/list} and
 * {@code tools/call}.
 *
 * <p>Implementations can be registered programmatically with
 * {@link ToolRegistry}, or discovered by the standalone launcher through
 * Java's {@link java.util.ServiceLoader} mechanism.
 */
public interface Tool {

  /** The unique MCP tool name. */
  String name();

  /** A short human-readable description of the tool. */
  default String description() {
    return "";
  }

  /**
   * JSON Schema describing the accepted arguments.
   *
   * @return an open object schema by default
   */
  default JsonObject schema() {
    return new JsonObject().put("type", "object");
  }

  /**
   * Invoke the tool.
   *
   * @param arguments arguments supplied by the MCP client
   * @param context   server-generated invocation metadata
   * @return the asynchronous tool result
   */
  Future<JsonObject> invoke(JsonObject arguments, ToolContext context);
}
