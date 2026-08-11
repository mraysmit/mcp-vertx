package dev.mars.mcp.tool;

import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Compiles and applies the JSON Schemas advertised by MCP tools. */
public final class ToolSchemaValidator {

  private final Map<String, Schema> schemas;

  public ToolSchemaValidator(Map<String, JsonObject> toolSchemas) {
    Map<String, Schema> compiled = new HashMap<>();
    toolSchemas.forEach((name, schema) -> compiled.put(name, compile(name, schema)));
    schemas = Map.copyOf(compiled);
  }

  /**
   * Validates tool arguments and returns a stable, human-readable error string.
   *
   * @return an empty string when the arguments are valid
   */
  public String validate(String toolName, JsonObject arguments) {
    Schema schema = schemas.get(toolName);
    if (schema == null) {
      throw new IllegalArgumentException("No schema is registered for tool: " + toolName);
    }
    List<Error> errors = schema.validate(arguments.encode(), InputFormat.JSON);
    return errors.stream()
        .sorted(Comparator.comparing((Error error) -> error.getInstanceLocation().toString())
            .thenComparing(Error::getMessage))
        .map(Error::getMessage)
        .collect(Collectors.joining("; "));
  }

  private Schema compile(String toolName, JsonObject schemaObject) {
    if (schemaObject == null) {
      throw new IllegalArgumentException("Tool " + toolName + " returned a null input schema");
    }
    validateSchemaSafety(toolName, schemaObject, 0);
    String declaredDraft = schemaObject.getString("$schema", "");
    SpecificationVersion version = declaredDraft.contains("draft-07")
        ? SpecificationVersion.DRAFT_7
        : SpecificationVersion.DRAFT_2020_12;
    try {
      return SchemaRegistry.withDefaultDialect(version)
          .getSchema(schemaObject.encode(), InputFormat.JSON);
    } catch (RuntimeException error) {
      throw new IllegalArgumentException(
          "Tool " + toolName + " returned an invalid input schema: " + error.getMessage(), error);
    }
  }

  private void validateSchemaSafety(String toolName, Object node, int depth) {
    if (depth > 64) {
      throw new IllegalArgumentException("Tool " + toolName + " input schema exceeds 64 levels");
    }
    if (node instanceof JsonObject object) {
      validateSchemaSafety(toolName, object.getMap(), depth);
      return;
    }
    if (node instanceof JsonArray array) {
      validateSchemaSafety(toolName, array.getList(), depth);
      return;
    }
    if (node instanceof Map<?, ?> map) {
      if (map.containsKey("x-mcp-header")) {
        throw new IllegalArgumentException(
            "Tool " + toolName + " input schema uses x-mcp-header, which this server does not support");
      }
      rejectExternalReference(toolName, map.get("$ref"), "$ref");
      rejectExternalReference(toolName, map.get("$dynamicRef"), "$dynamicRef");
      for (Object value : map.values()) {
        validateSchemaSafety(toolName, value, depth + 1);
      }
      return;
    }
    if (node instanceof List<?> list) {
      for (Object value : list) {
        validateSchemaSafety(toolName, value, depth + 1);
      }
    }
  }

  private void rejectExternalReference(String toolName, Object reference, String keyword) {
    if (reference == null) {
      return;
    }
    if (!(reference instanceof String value) || (!value.isEmpty() && !value.startsWith("#"))) {
      throw new IllegalArgumentException(
          "Tool " + toolName + " input schema uses an external " + keyword
              + "; only references within the advertised schema are supported");
    }
  }
}
