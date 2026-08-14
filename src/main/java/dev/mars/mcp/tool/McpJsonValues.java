package dev.mars.mcp.tool;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Defensive-copy utilities for values permitted by the JSON data model. */
public final class McpJsonValues {

  private McpJsonValues() {}

  /**
   * Returns a detached representation of a JSON value.
   *
   * @throws IllegalArgumentException when the value cannot be represented as JSON
   */
  public static Object copy(Object value) {
    if (value == null || value instanceof String || value instanceof Boolean
        || value instanceof Byte || value instanceof Short || value instanceof Integer
        || value instanceof Long || value instanceof Float || value instanceof Double
        || value instanceof BigInteger || value instanceof BigDecimal) {
      rejectNonFinite(value);
      return value;
    }
    if (value instanceof JsonObject object) {
      JsonObject copy = new JsonObject();
      object.forEach(entry -> copy.put(entry.getKey(), copy(entry.getValue())));
      return copy;
    }
    if (value instanceof JsonArray array) {
      JsonArray copy = new JsonArray();
      array.forEach(item -> copy.add(copy(item)));
      return copy;
    }
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> copy = new LinkedHashMap<>();
      map.forEach((key, item) -> {
        if (!(key instanceof String name)) {
          throw new IllegalArgumentException("JSON object keys must be strings");
        }
        copy.put(name, copy(item));
      });
      return new JsonObject(copy);
    }
    if (value instanceof List<?> list) {
      List<Object> copy = new ArrayList<>(list.size());
      list.forEach(item -> copy.add(copy(item)));
      return new JsonArray(copy);
    }
    throw new IllegalArgumentException(
        "Unsupported JSON value type: " + value.getClass().getName());
  }

  private static void rejectNonFinite(Object value) {
    boolean invalidDouble = value instanceof Double number && !Double.isFinite(number);
    boolean invalidFloat = value instanceof Float number && !Float.isFinite(number);
    if (invalidDouble || invalidFloat) {
      throw new IllegalArgumentException("JSON numbers must be finite");
    }
  }
}
