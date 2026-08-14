package dev.mars.mcp.tool;

import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.List;

/** A completed MCP tool result with rich or structured content. */
public final class CompleteToolResult implements ToolResult {

  private final List<ContentBlock> content;
  private final Object structuredContent;
  private final boolean structuredContentPresent;
  private final boolean isError;
  private final JsonObject metadata;

  public CompleteToolResult(List<ContentBlock> content, Object structuredContent,
                            boolean isError, JsonObject metadata) {
    this(content, structuredContent, structuredContent != null, isError, metadata);
  }

  private CompleteToolResult(List<ContentBlock> content, Object structuredContent,
                             boolean structuredContentPresent, boolean isError,
                             JsonObject metadata) {
    this.content = content == null ? List.of() : List.copyOf(content);
    if (this.content.stream().anyMatch(java.util.Objects::isNull)) {
      throw new IllegalArgumentException("Tool result content must not contain null blocks");
    }
    this.structuredContent = McpJsonValues.copy(structuredContent);
    this.structuredContentPresent = structuredContentPresent;
    this.isError = isError;
    this.metadata = metadata == null
        ? new JsonObject() : (JsonObject) McpJsonValues.copy(metadata);
    McpWireValidator.metadata(this.metadata);
  }

  public static CompleteToolResult structured(Object value) {
    if (value == null) {
      throw new IllegalArgumentException("Use structuredNull() for a JSON null result");
    }
    Object copy = McpJsonValues.copy(value);
    return new CompleteToolResult(List.of(ContentBlock.text(Json.encode(copy))), copy,
        true, false, new JsonObject());
  }

  /** Creates a completed result whose structured JSON value is explicitly null. */
  public static CompleteToolResult structuredNull() {
    return new CompleteToolResult(List.of(ContentBlock.text("null")), null,
        true, false, new JsonObject());
  }

  public static CompleteToolResult text(String text) {
    return new CompleteToolResult(List.of(ContentBlock.text(text)), null,
        false, false, new JsonObject());
  }

  public List<ContentBlock> content() {
    return content;
  }

  /** Returns a detached structured JSON value, or {@code null} when absent or JSON null. */
  public Object structuredContentValue() {
    return McpJsonValues.copy(structuredContent);
  }

  public boolean hasStructuredContent() {
    return structuredContentPresent;
  }

  /** Compatibility accessor for object-valued structured results. */
  public JsonObject structuredContent() {
    return structuredContent instanceof JsonObject object ? object.copy() : null;
  }

  public boolean isError() {
    return isError;
  }

  @Override
  public JsonObject metadata() {
    return metadata.copy();
  }

  @Override
  public JsonObject toJson() {
    JsonArray blocks = new JsonArray();
    content.forEach(block -> blocks.add(block.toJson()));
    JsonObject result = new JsonObject()
        .put("resultType", "complete")
        .put("content", blocks)
        .put("isError", isError);
    if (structuredContentPresent) {
      result.put("structuredContent", McpJsonValues.copy(structuredContent));
    }
    if (!metadata.isEmpty()) {
      result.put("_meta", metadata.copy());
    }
    return result;
  }
}
