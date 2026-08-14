package dev.mars.mcp.tool;

import io.vertx.core.json.JsonObject;

import java.util.Objects;

/** An immutable native MCP content block returned by a tool. */
public final class ContentBlock {

  private final JsonObject value;

  private ContentBlock(JsonObject value) {
    JsonObject copy = Objects.requireNonNull(value, "value").copy();
    McpWireValidator.contentBlock(copy);
    this.value = copy;
  }

  public JsonObject toJson() {
    return value.copy();
  }

  public static ContentBlock text(String text) {
    return new ContentBlock(new JsonObject().put("type", "text")
        .put("text", Objects.requireNonNull(text, "text")));
  }

  public static ContentBlock image(String base64Data, String mimeType) {
    return new ContentBlock(new JsonObject()
        .put("type", "image")
        .put("data", requireNonBlank(base64Data, "base64Data"))
        .put("mimeType", requireNonBlank(mimeType, "mimeType")));
  }

  public static ContentBlock audio(String base64Data, String mimeType) {
    return new ContentBlock(new JsonObject()
        .put("type", "audio")
        .put("data", requireNonBlank(base64Data, "base64Data"))
        .put("mimeType", requireNonBlank(mimeType, "mimeType")));
  }

  public static ContentBlock resourceLink(String name, String uri, String description,
                                          String mimeType) {
    JsonObject block = new JsonObject().put("type", "resource_link")
        .put("name", requireNonBlank(name, "name"))
        .put("uri", requireNonBlank(uri, "uri"));
    if (description != null) block.put("description", description);
    if (mimeType != null) block.put("mimeType", mimeType);
    return new ContentBlock(block);
  }

  /** Creates a defensively copied extension content block. */
  public static ContentBlock raw(JsonObject block) {
    return new ContentBlock(block);
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
