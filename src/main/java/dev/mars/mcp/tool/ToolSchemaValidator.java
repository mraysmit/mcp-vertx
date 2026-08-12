package dev.mars.mcp.tool;

import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import io.vertx.core.json.JsonObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Compiles and applies the JSON Schemas advertised by MCP tools. */
public final class ToolSchemaValidator {

  private static final Logger LOG = Logger.getLogger(ToolSchemaValidator.class.getName());

  private static final Set<String> DRAFT_2020_12 = Set.of(
      "https://json-schema.org/draft/2020-12/schema",
      "https://json-schema.org/draft/2020-12/schema#");
  private static final Set<String> DRAFT_7 = Set.of(
      "http://json-schema.org/draft-07/schema#",
      "https://json-schema.org/draft-07/schema#",
      "http://json-schema.org/draft-07/schema",
      "https://json-schema.org/draft-07/schema");
  private static final Pattern HEADER_TOKEN = Pattern.compile(
      "[!#$%&'*+.^_`|~0-9A-Za-z-]+");

  private final Map<String, Schema> inputSchemas;
  private final Map<String, Schema> outputSchemas;
  private final Map<String, List<HeaderBinding>> headerBindings;

  public ToolSchemaValidator(Map<String, JsonObject> toolSchemas) {
    this(toolSchemas, Map.of(), SchemaLimits.defaults());
  }

  public ToolSchemaValidator(Map<String, JsonObject> toolSchemas,
                             Map<String, JsonObject> toolOutputSchemas,
                             SchemaLimits limits) {
    LOG.info(() -> "Compiling MCP tool schemas: inputs=" + toolSchemas.size()
        + " outputs=" + toolOutputSchemas.size());
    LOG.fine(() -> "Schema safety limits: maxBytes=" + limits.maxBytes()
        + " maxDepth=" + limits.maxDepth() + " maxNodes=" + limits.maxNodes()
        + " maxCompositionBranches=" + limits.maxCompositionBranches()
        + " maxPropertiesPerObject=" + limits.maxPropertiesPerObject()
        + " maxRegexLength=" + limits.maxRegexLength());
    Map<String, Schema> compiledInputs = new HashMap<>();
    Map<String, Schema> compiledOutputs = new HashMap<>();
    Map<String, List<HeaderBinding>> bindings = new HashMap<>();

    toolSchemas.forEach((name, schema) -> {
      Analysis analysis = analyze(name, schema, limits, true);
      compiledInputs.put(name, compile(name, "input", schema, analysis.version()));
      bindings.put(name, analysis.headerBindings());
    });
    toolOutputSchemas.forEach((name, schema) -> {
      Analysis analysis = analyze(name, schema, limits, false);
      compiledOutputs.put(name, compile(name, "output", schema, analysis.version()));
    });

    inputSchemas = Map.copyOf(compiledInputs);
    outputSchemas = Map.copyOf(compiledOutputs);
    headerBindings = Map.copyOf(bindings);
    LOG.info(() -> "MCP tool schemas compiled: inputs=" + inputSchemas.size()
        + " outputs=" + outputSchemas.size()
        + " mirroredHeaders=" + headerBindings.values().stream()
            .mapToInt(List::size).sum());
  }

  /** Returns an empty string when the arguments are valid. */
  public String validate(String toolName, JsonObject arguments) {
    LOG.fine(() -> "Validating MCP tool input: tool=" + toolName);
    String result = validateWith(requireSchema(inputSchemas, toolName, "input"), arguments);
    logValidationResult(toolName, "input", result);
    return result;
  }

  /** Returns an empty string when no output schema exists or output is valid. */
  public String validateOutput(String toolName, JsonObject output) {
    Schema schema = outputSchemas.get(toolName);
    if (schema == null) {
      LOG.fine(() -> "Skipping MCP tool output validation; no schema advertised: tool="
          + toolName);
      return "";
    }
    LOG.fine(() -> "Validating MCP tool output: tool=" + toolName);
    String result = validateWith(schema, output);
    logValidationResult(toolName, "output", result);
    return result;
  }

  public List<HeaderBinding> headerBindings(String toolName) {
    return headerBindings.getOrDefault(toolName, List.of());
  }

  private String validateWith(Schema schema, JsonObject value) {
    List<Error> errors = schema.validate(value.encode(), InputFormat.JSON);
    return errors.stream()
        .sorted(Comparator.comparing((Error error) -> error.getInstanceLocation().toString())
            .thenComparing(Error::getMessage))
        .map(Error::getMessage)
        .collect(Collectors.joining("; "));
  }

  private Schema requireSchema(Map<String, Schema> schemas, String toolName, String kind) {
    Schema schema = schemas.get(toolName);
    if (schema == null) {
      throw new IllegalArgumentException("No " + kind + " schema is registered for tool: " + toolName);
    }
    return schema;
  }

  private Schema compile(String toolName, String kind, JsonObject schemaObject,
                         SpecificationVersion version) {
    try {
      return SchemaRegistry.withDefaultDialect(version)
          .getSchema(schemaObject.encode(), InputFormat.JSON);
    } catch (RuntimeException error) {
      throw new IllegalArgumentException(
          "Tool " + toolName + " returned an invalid " + kind
              + " schema: " + error.getMessage(), error);
    }
  }

  private Analysis analyze(String toolName, JsonObject schema, SchemaLimits limits,
                           boolean collectHeaders) {
    if (schema == null) {
      throw new IllegalArgumentException("Tool " + toolName + " returned a null schema");
    }
    int bytes = schema.encode().getBytes(StandardCharsets.UTF_8).length;
    if (bytes > limits.maxBytes()) {
      throw invalid(toolName, "schema exceeds " + limits.maxBytes() + " UTF-8 bytes");
    }

    SpecificationVersion version = dialect(toolName, schema.getValue("$schema"));
    Counters counters = new Counters();
    Map<String, HeaderBinding> headers = new LinkedHashMap<>();
    walk(toolName, schema.getMap(), 0, List.of(), false, collectHeaders,
        version, limits, counters, headers);
    LOG.fine(() -> "Analyzed MCP tool schema: tool=" + toolName
        + " kind=" + (collectHeaders ? "input" : "output")
        + " dialect=" + version + " bytes=" + bytes + " nodes=" + counters.nodes
        + " compositionBranches=" + counters.compositionBranches
        + " mirroredHeaders=" + headers.size());
    return new Analysis(version, List.copyOf(headers.values()));
  }

  private void logValidationResult(String toolName, String kind, String result) {
    if (result.isEmpty()) {
      LOG.fine(() -> "MCP tool schema validation passed: tool=" + toolName
          + " kind=" + kind);
    } else {
      LOG.info(() -> "MCP tool schema validation rejected value: tool=" + toolName
          + " kind=" + kind + " violations=" + result.split("; ").length);
      LOG.fine(() -> "MCP tool schema violations: tool=" + toolName
          + " kind=" + kind + " detailChars=" + result.length());
    }
  }

  private void walk(String toolName, Object node, int depth, List<String> path,
                    boolean headerAllowed, boolean collectHeaders,
                    SpecificationVersion rootVersion, SchemaLimits limits,
                    Counters counters, Map<String, HeaderBinding> headers) {
    if (node instanceof JsonObject object) {
      node = object.getMap();
    } else if (node instanceof io.vertx.core.json.JsonArray array) {
      node = array.getList();
    }
    if (depth > limits.maxDepth()) {
      throw invalid(toolName, "schema exceeds " + limits.maxDepth() + " levels");
    }
    if (++counters.nodes > limits.maxNodes()) {
      throw invalid(toolName, "schema exceeds " + limits.maxNodes() + " nodes");
    }

    if (node instanceof Map<?, ?> map) {
      rejectExternalReference(toolName, map.get("$ref"), "$ref");
      rejectExternalReference(toolName, map.get("$dynamicRef"), "$dynamicRef");

      if (map.containsKey("$schema")) {
        SpecificationVersion nested = dialect(toolName, map.get("$schema"));
        if (nested != rootVersion) {
          throw invalid(toolName, "nested schemas cannot change JSON Schema dialect");
        }
      }
      if (map.containsKey("pattern") && map.get("pattern") instanceof String pattern
          && pattern.length() > limits.maxRegexLength()) {
        throw invalid(toolName, "schema regex exceeds " + limits.maxRegexLength() + " characters");
      }
      for (String key : List.of("oneOf", "anyOf", "allOf")) {
        int branchCount = containerSize(map.get(key));
        if (branchCount >= 0) {
          counters.compositionBranches += branchCount;
          if (counters.compositionBranches > limits.maxCompositionBranches()) {
            throw invalid(toolName, "schema exceeds " + limits.maxCompositionBranches()
                + " composition branches");
          }
        }
      }

      Object annotation = map.get("x-mcp-header");
      if (annotation != null) {
        if (!collectHeaders) {
          throw invalid(toolName, "output schemas cannot use x-mcp-header");
        }
        if (!headerAllowed || path.isEmpty()) {
          throw invalid(toolName, "x-mcp-header is not on a statically reachable property");
        }
        HeaderBinding binding = headerBinding(toolName, path, annotation, map.get("type"));
        String key = binding.name().toLowerCase(Locale.ROOT);
        if (headers.putIfAbsent(key, binding) != null) {
          throw invalid(toolName, "x-mcp-header values must be case-insensitively unique: "
              + binding.name());
        }
      }

      Object rawProperties = map.get("properties");
      Map<?, ?> properties = rawProperties instanceof JsonObject object
          ? object.getMap()
          : rawProperties instanceof Map<?, ?> rawMap ? rawMap : null;
      if (properties != null) {
        if (properties.size() > limits.maxPropertiesPerObject()) {
          throw invalid(toolName, "schema object exceeds " + limits.maxPropertiesPerObject()
              + " properties");
        }
        for (Map.Entry<?, ?> entry : properties.entrySet()) {
          List<String> childPath = new ArrayList<>(path);
          childPath.add(String.valueOf(entry.getKey()));
          walk(toolName, entry.getValue(), depth + 1, List.copyOf(childPath), true,
              collectHeaders, rootVersion, limits, counters, headers);
        }
      }

      for (Map.Entry<?, ?> entry : map.entrySet()) {
        String key = String.valueOf(entry.getKey());
        if (!"properties".equals(key) && !"x-mcp-header".equals(key)) {
          walk(toolName, entry.getValue(), depth + 1, path, false, collectHeaders,
              rootVersion, limits, counters, headers);
        }
      }
      return;
    }
    if (node instanceof List<?> list) {
      for (Object value : list) {
        walk(toolName, value, depth + 1, path, false, collectHeaders,
            rootVersion, limits, counters, headers);
      }
    }
  }

  private int containerSize(Object value) {
    if (value instanceof io.vertx.core.json.JsonArray array) return array.size();
    if (value instanceof List<?> list) return list.size();
    return -1;
  }

  private HeaderBinding headerBinding(String toolName, List<String> path,
                                      Object annotation, Object rawType) {
    if (!(annotation instanceof String name) || name.isBlank()
        || !HEADER_TOKEN.matcher(name).matches()) {
      throw invalid(toolName, "x-mcp-header must be a non-empty HTTP field-name token");
    }
    if (!(rawType instanceof String typeName)) {
      throw invalid(toolName, "x-mcp-header properties require an explicit primitive type");
    }
    HeaderValueType type = switch (typeName) {
      case "string" -> HeaderValueType.STRING;
      case "integer" -> HeaderValueType.INTEGER;
      case "boolean" -> HeaderValueType.BOOLEAN;
      default -> throw invalid(toolName,
          "x-mcp-header supports only string, integer, and boolean properties");
    };
    return new HeaderBinding(name, List.copyOf(path), type);
  }

  private SpecificationVersion dialect(String toolName, Object declared) {
    if (declared == null) {
      return SpecificationVersion.DRAFT_2020_12;
    }
    if (!(declared instanceof String value)) {
      throw invalid(toolName, "$schema must be a string");
    }
    if (DRAFT_2020_12.contains(value)) return SpecificationVersion.DRAFT_2020_12;
    if (DRAFT_7.contains(value)) return SpecificationVersion.DRAFT_7;
    throw invalid(toolName, "unsupported JSON Schema dialect: " + value
        + "; supported dialects are 2020-12 and draft-07");
  }

  private void rejectExternalReference(String toolName, Object reference, String keyword) {
    if (reference == null) return;
    if (!(reference instanceof String value) || (!value.isEmpty() && !value.startsWith("#"))) {
      throw invalid(toolName, "schema uses an external " + keyword
          + "; only references within the advertised schema are supported");
    }
  }

  private IllegalArgumentException invalid(String toolName, String message) {
    return new IllegalArgumentException("Tool " + toolName + " " + message);
  }

  public record HeaderBinding(String name, List<String> path, HeaderValueType type) {}

  public enum HeaderValueType { STRING, INTEGER, BOOLEAN }

  public record SchemaLimits(
      int maxDepth,
      int maxNodes,
      int maxCompositionBranches,
      int maxPropertiesPerObject,
      int maxRegexLength,
      int maxBytes) {

    public SchemaLimits {
      if (maxDepth <= 0 || maxNodes <= 0 || maxCompositionBranches <= 0
          || maxPropertiesPerObject <= 0 || maxRegexLength <= 0 || maxBytes <= 0) {
        throw new IllegalArgumentException("Schema limits must be positive");
      }
    }

    public static SchemaLimits defaults() {
      return new SchemaLimits(64, 5_000, 500, 1_000, 4_096, 262_144);
    }
  }

  private record Analysis(SpecificationVersion version,
                          List<HeaderBinding> headerBindings) {}

  private static final class Counters {
    int nodes;
    int compositionBranches;
  }
}
