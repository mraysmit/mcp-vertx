package dev.mars.mcp.tool;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolRegistryTest {

  private Tool stubTool(String name) {
    return new Tool() {
      @Override
      public String name() { return name; }
      @Override
      public Future<JsonObject> invoke(JsonObject args, ToolContext ctx) {
        return Future.succeededFuture(new JsonObject());
      }
    };
  }

  @Test
  void default_schema_is_open_object() {
    Tool tool = stubTool("test");
    assertEquals("object", tool.schema().getString("type"));
    assertNull(tool.schema().getJsonObject("properties"));
  }

  @Test
  void default_description_is_empty() {
    Tool tool = stubTool("test");
    assertEquals("", tool.description());
  }

  @Test
  void of_creates_registry_from_tools() {
    Map<String, Tool> registry = ToolRegistry.of(stubTool("a"), stubTool("b"));
    assertEquals(2, registry.size());
    assertTrue(registry.containsKey("a"));
    assertTrue(registry.containsKey("b"));
  }

  @Test
  void of_returns_immutable_map() {
    Map<String, Tool> registry = ToolRegistry.of(stubTool("a"));
    assertThrows(UnsupportedOperationException.class,
        () -> registry.put("b", stubTool("b")));
  }

  @Test
  void of_empty_creates_empty_registry() {
    Map<String, Tool> registry = ToolRegistry.of();
    assertTrue(registry.isEmpty());
  }

  @Test
  void of_duplicate_names_throws() {
    assertThrows(IllegalStateException.class,
        () -> ToolRegistry.of(stubTool("dup"), stubTool("dup")));
  }

  @Test
  void with_additional_merges_tools() {
    Map<String, Tool> base = ToolRegistry.of(stubTool("a"));
    Map<String, Tool> merged = ToolRegistry.withAdditional(base, stubTool("b"), stubTool("c"));
    assertEquals(3, merged.size());
    assertTrue(merged.containsKey("a"));
    assertTrue(merged.containsKey("b"));
    assertTrue(merged.containsKey("c"));
  }

  @Test
  void with_additional_overrides_existing_key() {
    Tool original = stubTool("x");
    Tool replacement = stubTool("x");
    Map<String, Tool> base = ToolRegistry.of(original);
    Map<String, Tool> merged = ToolRegistry.withAdditional(base, replacement);
    assertSame(replacement, merged.get("x"));
  }

  @Test
  void with_additional_returns_immutable_map() {
    Map<String, Tool> base = ToolRegistry.of(stubTool("a"));
    Map<String, Tool> merged = ToolRegistry.withAdditional(base, stubTool("b"));
    assertThrows(UnsupportedOperationException.class,
        () -> merged.put("c", stubTool("c")));
  }
}
