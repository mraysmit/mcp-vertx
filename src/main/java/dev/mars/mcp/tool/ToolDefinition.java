package dev.mars.mcp.tool;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/** Immutable metadata advertised for a tool. */
public record ToolDefinition(
    String name,
    String title,
    String description,
    JsonObject inputSchema,
    JsonObject outputSchema,
    JsonArray icons,
    JsonObject annotations,
    JsonObject execution) {

  public ToolDefinition {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("Tool name must not be blank");
    }
    description = description == null ? "" : description;
    inputSchema = inputSchema == null
        ? new JsonObject().put("type", "object") : inputSchema.copy();
    outputSchema = outputSchema == null ? null : outputSchema.copy();
    icons = icons == null ? null : (JsonArray) McpJsonValues.copy(icons);
    annotations = annotations == null
        ? null : (JsonObject) McpJsonValues.copy(annotations);
    execution = execution == null ? null : (JsonObject) McpJsonValues.copy(execution);
    McpWireValidator.icons(icons);
    McpWireValidator.toolAnnotations(annotations);
    if (execution != null) McpJsonValues.copy(execution);
  }

  public JsonObject toJson() {
    JsonObject json = new JsonObject().put("name", name)
        .put("description", description).put("inputSchema", inputSchema.copy());
    if (title != null && !title.isBlank()) json.put("title", title);
    if (outputSchema != null) json.put("outputSchema", outputSchema.copy());
    if (icons != null) json.put("icons", icons.copy());
    if (annotations != null) json.put("annotations", annotations.copy());
    if (execution != null) json.put("execution", execution.copy());
    return json;
  }

  @Override public JsonObject inputSchema() { return inputSchema.copy(); }
  @Override public JsonObject outputSchema() {
    return outputSchema == null ? null : outputSchema.copy();
  }
  @Override public JsonArray icons() { return icons == null ? null : icons.copy(); }
  @Override public JsonObject annotations() {
    return annotations == null ? null : annotations.copy();
  }
  @Override public JsonObject execution() {
    return execution == null ? null : execution.copy();
  }

  public static Builder builder(String name) {
    return new Builder(name);
  }

  public static final class Builder {
    private final String name;
    private String title;
    private String description = "";
    private JsonObject inputSchema = new JsonObject().put("type", "object");
    private JsonObject outputSchema;
    private JsonArray icons;
    private JsonObject annotations;
    private JsonObject execution;

    private Builder(String name) { this.name = name; }
    public Builder title(String value) { title = value; return this; }
    public Builder description(String value) { description = value; return this; }
    public Builder inputSchema(JsonObject value) { inputSchema = value; return this; }
    public Builder outputSchema(JsonObject value) { outputSchema = value; return this; }
    public Builder icons(JsonArray value) { icons = value; return this; }
    public Builder annotations(JsonObject value) { annotations = value; return this; }
    public Builder execution(JsonObject value) { execution = value; return this; }
    public ToolDefinition build() {
      return new ToolDefinition(name, title, description, inputSchema, outputSchema,
          icons, annotations, execution);
    }
  }
}
