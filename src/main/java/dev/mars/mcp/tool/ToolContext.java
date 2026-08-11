package dev.mars.mcp.tool;

import io.vertx.core.json.JsonObject;

/**
 * Immutable metadata supplied to a tool invocation by the MCP server.
 *
 * @param correlationId unique identifier for this invocation
 * @param resourceId    optional caller-supplied resource identifier, falling
 *                      back to the correlation ID when absent
 * @param metadata      extensible invocation metadata
 */
public record ToolContext(
    String correlationId,
    String resourceId,
    JsonObject metadata) {}
