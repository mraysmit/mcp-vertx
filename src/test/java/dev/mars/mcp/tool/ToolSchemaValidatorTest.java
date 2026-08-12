package dev.mars.mcp.tool;

import dev.mars.mcp.testing.TestLoggingExtension;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(TestLoggingExtension.class)
class ToolSchemaValidatorTest {

  @Test
  void rejects_unknown_schema_dialects_instead_of_guessing() {
    JsonObject schema = new JsonObject()
        .put("$schema", "https://example.test/a-private-dialect")
        .put("type", "object");

    IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
        () -> new ToolSchemaValidator(Map.of("test", schema)));

    assertTrue(error.getMessage().contains("unsupported JSON Schema dialect"));
  }

  @Test
  void rejects_case_insensitive_duplicate_mirrored_headers() {
    JsonObject schema = new JsonObject().put("type", "object")
        .put("properties", new JsonObject()
            .put("first", new JsonObject().put("type", "string")
                .put("x-mcp-header", "Region"))
            .put("second", new JsonObject().put("type", "integer")
                .put("x-mcp-header", "region")));

    IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
        () -> new ToolSchemaValidator(Map.of("test", schema)));

    assertTrue(error.getMessage().contains("case-insensitively unique"));
  }

  @Test
  void compiles_draft_7_and_2020_12_explicitly() {
    JsonObject draft7 = new JsonObject()
        .put("$schema", "http://json-schema.org/draft-07/schema#")
        .put("type", "object");
    JsonObject current = new JsonObject()
        .put("$schema", "https://json-schema.org/draft/2020-12/schema")
        .put("type", "object");

    ToolSchemaValidator validator = new ToolSchemaValidator(
        Map.of("draft7", draft7, "current", current));

    assertEquals("", validator.validate("draft7", new JsonObject()));
    assertEquals("", validator.validate("current", new JsonObject()));
  }

  @Test
  void enforces_each_schema_complexity_limit() {
    ToolSchemaValidator.SchemaLimits tight =
        new ToolSchemaValidator.SchemaLimits(2, 20, 1, 1, 2, 200);

    assertThrows(IllegalArgumentException.class, () -> validator(
        new JsonObject().put("description", "x".repeat(300)), tight));
    assertThrows(IllegalArgumentException.class, () -> validator(
        new JsonObject().put("type", "object").put("properties", new JsonObject()
            .put("nested", new JsonObject().put("type", "object")
                .put("properties", new JsonObject().put("deep",
                    new JsonObject().put("type", "string"))))), tight));
    assertThrows(IllegalArgumentException.class, () -> validator(
        new JsonObject().put("anyOf", new JsonArray()
            .add(new JsonObject().put("type", "string"))
            .add(new JsonObject().put("type", "number"))), tight));
    assertThrows(IllegalArgumentException.class, () -> validator(
        new JsonObject().put("type", "object").put("properties", new JsonObject()
            .put("first", new JsonObject()).put("second", new JsonObject())), tight));
    assertThrows(IllegalArgumentException.class, () -> validator(
        new JsonObject().put("type", "string").put("pattern", "long"), tight));
    assertThrows(IllegalArgumentException.class,
        () -> new ToolSchemaValidator.SchemaLimits(0, 1, 1, 1, 1, 1));
  }

  @Test
  void rejects_unsafe_dialect_reference_and_header_shapes() {
    assertThrows(IllegalArgumentException.class, () -> new ToolSchemaValidator(
        Map.of("test", new JsonObject().put("$schema", 42))));
    assertThrows(IllegalArgumentException.class, () -> new ToolSchemaValidator(
        Map.of("test", new JsonObject()
            .put("$schema", "https://json-schema.org/draft/2020-12/schema")
            .put("properties", new JsonObject().put("value", new JsonObject()
                .put("$schema", "http://json-schema.org/draft-07/schema#"))))));
    assertThrows(IllegalArgumentException.class, () -> new ToolSchemaValidator(
        Map.of("test", new JsonObject().put("$dynamicRef", "https://example.test/schema"))));
    assertThrows(IllegalArgumentException.class, () -> new ToolSchemaValidator(
        Map.of("test", new JsonObject().put("type", "string")
            .put("x-mcp-header", "Root"))));
    assertThrows(IllegalArgumentException.class, () -> new ToolSchemaValidator(
        Map.of("test", headerProperty("array", "Items"))));
    assertThrows(IllegalArgumentException.class, () -> new ToolSchemaValidator(
        Map.of("test", headerProperty("string", "bad header"))));
    assertThrows(IllegalArgumentException.class, () -> new ToolSchemaValidator(
        Map.of("test", new JsonObject().put("type", "object")),
        Map.of("test", headerProperty("string", "Output")),
        ToolSchemaValidator.SchemaLimits.defaults()));
  }

  @Test
  void validates_optional_outputs_and_internal_references() {
    JsonObject input = new JsonObject().put("$defs", new JsonObject().put("name",
        new JsonObject().put("type", "string")))
        .put("type", "object")
        .put("properties", new JsonObject().put("name",
            new JsonObject().put("$ref", "#/$defs/name")));
    JsonObject output = new JsonObject().put("type", "object")
        .put("properties", new JsonObject().put("ok", new JsonObject().put("type", "boolean")))
        .put("required", new JsonArray().add("ok"));
    ToolSchemaValidator validator = new ToolSchemaValidator(
        Map.of("test", input), Map.of("test", output),
        ToolSchemaValidator.SchemaLimits.defaults());

    assertEquals("", validator.validate("test", new JsonObject().put("name", "valid")));
    assertEquals("", validator.validateOutput("test", new JsonObject().put("ok", true)));
    assertTrue(validator.validateOutput("test", new JsonObject().put("ok", "yes"))
        .contains("boolean expected"));
    assertEquals("", new ToolSchemaValidator(Map.of("test", input))
        .validateOutput("test", new JsonObject()));
    assertThrows(IllegalArgumentException.class,
        () -> validator.validate("unknown", new JsonObject()));
  }

  private ToolSchemaValidator validator(JsonObject schema,
                                        ToolSchemaValidator.SchemaLimits limits) {
    return new ToolSchemaValidator(Map.of("test", schema), Map.of(), limits);
  }

  private JsonObject headerProperty(String type, String header) {
    return new JsonObject().put("type", "object")
        .put("properties", new JsonObject().put("value", new JsonObject()
            .put("type", type).put("x-mcp-header", header)));
  }
}
