package dev.mars.mcp.tool;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;

/** Validation shared by provider-controlled MCP wire structures. */
final class McpWireValidator {

  private McpWireValidator() {}

  static void contentBlock(JsonObject block) {
    String type = string(block, "type", true);
    switch (type) {
      case "text" -> string(block, "text", false);
      case "image", "audio" -> {
        string(block, "data", true);
        string(block, "mimeType", true);
      }
      case "resource_link" -> {
        string(block, "name", true);
        uri(block, "uri");
        optionalString(block, "description");
        optionalString(block, "mimeType");
      }
      case "resource" -> embeddedResource(block);
      default -> { /* Extension content types are allowed with a valid discriminator. */ }
    }
    if (block.containsKey("annotations")) annotations(block.getValue("annotations"));
  }

  static void metadata(JsonObject metadata) {
    metadata.forEach(entry -> {
      String key = entry.getKey();
      if (key == null || key.length() > 255 || key.chars().anyMatch(Character::isISOControl)) {
        throw new IllegalArgumentException("MCP metadata contains an invalid key");
      }
      McpJsonValues.copy(entry.getValue());
    });
  }

  static void icons(JsonArray icons) {
    if (icons == null) return;
    icons.forEach(value -> {
      if (!(value instanceof JsonObject icon)) {
        throw new IllegalArgumentException("Tool icons must be objects");
      }
      String source = string(icon, "src", true);
      URI uri = parseUri(source, "icon src");
      String scheme = uri.getScheme();
      if (scheme == null || !("https".equalsIgnoreCase(scheme)
          || "http".equalsIgnoreCase(scheme) || "data".equalsIgnoreCase(scheme))) {
        throw new IllegalArgumentException("Tool icon src must use http, https, or data");
      }
      optionalString(icon, "mimeType");
      if (icon.containsKey("theme")
          && !("light".equals(icon.getValue("theme")) || "dark".equals(icon.getValue("theme")))) {
        throw new IllegalArgumentException("Tool icon theme must be light or dark");
      }
      if (icon.containsKey("sizes")) {
        if (!(icon.getValue("sizes") instanceof JsonArray sizes)) {
          throw new IllegalArgumentException("Tool icon sizes must be an array");
        }
        sizes.forEach(size -> {
          if (!(size instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("Tool icon sizes must contain strings");
          }
        });
      }
    });
  }

  static void toolAnnotations(JsonObject annotations) {
    if (annotations == null) return;
    optionalString(annotations, "title");
    for (String field : List.of(
        "readOnlyHint", "destructiveHint", "idempotentHint", "openWorldHint")) {
      if (annotations.containsKey(field) && !(annotations.getValue(field) instanceof Boolean)) {
        throw new IllegalArgumentException("Tool annotation " + field + " must be boolean");
      }
    }
  }

  private static void embeddedResource(JsonObject block) {
    if (!(block.getValue("resource") instanceof JsonObject resource)) {
      throw new IllegalArgumentException("Embedded resource content requires a resource object");
    }
    uri(resource, "uri");
    optionalString(resource, "mimeType");
    boolean text = resource.getValue("text") instanceof String;
    boolean blob = resource.getValue("blob") instanceof String value && !value.isBlank();
    if (text == blob) {
      throw new IllegalArgumentException(
          "Embedded resource must contain exactly one of text or blob");
    }
  }

  private static void annotations(Object raw) {
    if (!(raw instanceof JsonObject annotations)) {
      throw new IllegalArgumentException("Content annotations must be an object");
    }
    if (annotations.containsKey("audience")) {
      if (!(annotations.getValue("audience") instanceof JsonArray audience)) {
        throw new IllegalArgumentException("Content annotation audience must be an array");
      }
      audience.forEach(role -> {
        if (!("user".equals(role) || "assistant".equals(role))) {
          throw new IllegalArgumentException("Content annotation audience has an invalid role");
        }
      });
    }
    if (annotations.containsKey("priority")) {
      if (!(annotations.getValue("priority") instanceof Number number)
          || number.doubleValue() < 0 || number.doubleValue() > 1) {
        throw new IllegalArgumentException("Content annotation priority must be between 0 and 1");
      }
    }
    optionalString(annotations, "lastModified");
  }

  private static void uri(JsonObject object, String field) {
    parseUri(string(object, field, true), field);
  }

  private static URI parseUri(String value, String field) {
    try {
      URI uri = new URI(value);
      if (!uri.isAbsolute()) throw new IllegalArgumentException(field + " must be an absolute URI");
      return uri;
    } catch (URISyntaxException error) {
      throw new IllegalArgumentException(field + " must be a valid URI", error);
    }
  }

  private static void optionalString(JsonObject object, String field) {
    if (object.containsKey(field) && !(object.getValue(field) instanceof String)) {
      throw new IllegalArgumentException(field + " must be a string");
    }
  }

  private static String string(JsonObject object, String field, boolean nonBlank) {
    if (!(object.getValue(field) instanceof String value) || nonBlank && value.isBlank()) {
      throw new IllegalArgumentException(field + " must be "
          + (nonBlank ? "a non-blank" : "a") + " string");
    }
    return value;
  }
}
