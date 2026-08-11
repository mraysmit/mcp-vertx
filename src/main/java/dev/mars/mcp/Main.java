package dev.mars.mcp;

import dev.mars.mcp.tool.Tool;
import dev.mars.mcp.tool.ToolRegistry;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.logging.Logger;

/** Standalone entry point for the Vert.x MCP server. */
public final class Main {

  private static final Logger LOG = Logger.getLogger(Main.class.getName());

  private Main() {}

  public static void main(String[] args) {
    List<Tool> discoveredTools = new ArrayList<>();
    ServiceLoader.load(Tool.class).forEach(discoveredTools::add);

    String resourceIdField = setting(
        "mcp.resourceIdField", "MCP_RESOURCE_ID_FIELD", "resourceId");
    JsonObject config = new JsonObject()
        .put("mcp.port", integerSetting("mcp.port", "MCP_PORT", 3001))
        .put("mcp.basePath", setting("mcp.basePath", "MCP_BASE_PATH", ""));

    Vertx vertx = Vertx.vertx();
    Runtime.getRuntime().addShutdownHook(
        new Thread(() -> vertx.close(), "mcp-vertx-shutdown"));

    var tools = ToolRegistry.of(discoveredTools.toArray(Tool[]::new));
    vertx.deployVerticle(
        new McpServerVerticle(tools, resourceIdField),
        new DeploymentOptions().setConfig(config))
      .onSuccess(id -> LOG.info("MCP server deployed with " + tools.size() + " tool(s)"))
      .onFailure(error -> {
        LOG.severe("Unable to start MCP server: " + error.getMessage());
        error.printStackTrace();
        vertx.close();
      });
  }

  private static String setting(String property, String environment, String fallback) {
    String propertyValue = System.getProperty(property);
    if (propertyValue != null && !propertyValue.isBlank()) {
      return propertyValue;
    }
    String environmentValue = System.getenv(environment);
    return environmentValue == null || environmentValue.isBlank()
        ? fallback
        : environmentValue;
  }

  private static int integerSetting(String property, String environment, int fallback) {
    String value = setting(property, environment, Integer.toString(fallback));
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException error) {
      throw new IllegalArgumentException(
          property + "/" + environment + " must be an integer: " + value,
          error);
    }
  }
}
