package dev.mars.mcp.tool;

import io.vertx.core.json.JsonObject;

/** A native MCP result returned by an advanced tool. */
public sealed interface ToolResult permits CompleteToolResult, InputRequiredToolResult {
  JsonObject toJson();
  JsonObject metadata();
}
