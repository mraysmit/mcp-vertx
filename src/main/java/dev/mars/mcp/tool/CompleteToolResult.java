package dev.mars.mcp.tool;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.List;

/** A completed MCP tool result with rich or structured content. */
public record CompleteToolResult(
    List<ContentBlock> content,
    JsonObject structuredContent,
    boolean isError,
    JsonObject metadata) implements ToolResult {

  public CompleteToolResult {
    content = content == null ? List.of() : List.copyOf(content);
    structuredContent = structuredContent == null ? null : structuredContent.copy();
    metadata = metadata == null ? new JsonObject() : metadata.copy();
  }

  public static CompleteToolResult structured(JsonObject value) {
    if (value == null) {
      throw new IllegalArgumentException("Tool returned no result");
    }
    return new CompleteToolResult(List.of(ContentBlock.text(value.encode())), value,
        false, new JsonObject());
  }

  public static CompleteToolResult text(String text) {
    return new CompleteToolResult(List.of(ContentBlock.text(text)), null, false,
        new JsonObject());
  }

  @Override
  public JsonObject metadata() {
    return metadata.copy();
  }

  @Override
  public JsonObject structuredContent() {
    return structuredContent == null ? null : structuredContent.copy();
  }

  @Override
  public JsonObject toJson() {
    JsonArray blocks = new JsonArray();
    content.forEach(block -> blocks.add(block.toJson()));
    JsonObject result = new JsonObject()
        .put("resultType", "complete")
        .put("content", blocks)
        .put("isError", isError);
    if (structuredContent != null) {
      result.put("structuredContent", structuredContent.copy());
    }
    if (!metadata.isEmpty()) {
      result.put("_meta", metadata.copy());
    }
    return result;
  }
}
