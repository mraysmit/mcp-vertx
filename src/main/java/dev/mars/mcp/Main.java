package dev.mars.mcp;

import dev.mars.mcp.tool.Tool;
import dev.mars.mcp.tool.ToolRegistry;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/** Standalone entry point for the Vert.x MCP server. */
public final class Main {

  static final String VERTX_LOGGER_FACTORY_PROPERTY =
      "vertx.logger-delegate-factory-class-name";
  static final String SLF4J_LOGGER_FACTORY =
      "io.vertx.core.logging.SLF4JLogDelegateFactory";
  private static final Logger LOG = LoggerFactory.getLogger(Main.class);

  private Main() {}

  public static void main(String[] args) {
    configureVertxLogging();
    LOG.info("Starting mcp-vertx standalone server");
    List<Tool> discoveredTools = new ArrayList<>();
    ServiceLoader.load(Tool.class).forEach(discoveredTools::add);
    LOG.atInfo().log(() -> "Discovered " + discoveredTools.size() + " MCP tool provider(s)");

    String resourceIdField = resourceIdField();
    JsonObject config = configuration();
    LOG.atDebug().log(() -> "Resolved MCP configuration: host=" + config.getString("mcp.host")
        + " port=" + config.getInteger("mcp.port")
        + " basePath=\"" + config.getString("mcp.basePath") + "\""
        + " authConfigured=" + !config.getString("mcp.authToken").isBlank()
        + " oauthEnabled=" + config.getBoolean("mcp.oauth.enabled")
        + " healthEnabled=" + config.getBoolean("mcp.healthEnabled")
        + " resourceIdField=" + resourceIdField);

    Vertx vertx = Vertx.vertx();
    LOG.debug("Created Vert.x runtime and installing JVM shutdown hook");
    Runtime.getRuntime().addShutdownHook(
        new Thread(() -> {
          LOG.info("JVM shutdown requested; closing Vert.x runtime");
          try {
            vertx.close().toCompletionStage().toCompletableFuture().join();
            LOG.info("Vert.x runtime closed");
          } catch (RuntimeException error) {
            LOG.warn("Vert.x shutdown failed", error);
          }
        }, "mcp-vertx-shutdown"));

    var tools = ToolRegistry.of(discoveredTools.toArray(Tool[]::new));
    LOG.atDebug().log(() -> "Validated MCP tool registrations: "
        + tools.keySet().stream().sorted().toList());
    LOG.atDebug().log(() -> "Deploying MCP server verticle with " + tools.size() + " registered tool(s)");
    vertx.deployVerticle(
        new McpServerVerticle(tools, resourceIdField),
        new DeploymentOptions().setConfig(config))
      .onSuccess(id -> LOG.info("MCP server deployed: deploymentId=" + id
          + " tools=" + tools.size()))
      .onFailure(error -> {
        LOG.error("Unable to start MCP server", error);
        vertx.close()
            .onSuccess(ignored -> LOG.debug("Vert.x runtime closed after startup failure"))
            .onFailure(closeError -> LOG.warn(
                "Vert.x close failed after startup failure", closeError))
            .onComplete(ignored -> System.exit(1));
      });
  }

  static void configureVertxLogging() {
    if (System.getProperty(VERTX_LOGGER_FACTORY_PROPERTY) == null) {
      System.setProperty(VERTX_LOGGER_FACTORY_PROPERTY, SLF4J_LOGGER_FACTORY);
    }
    LOG.debug("Vert.x logging backend configured: delegateFactory={}",
        System.getProperty(VERTX_LOGGER_FACTORY_PROPERTY));
  }

  static String resourceIdField() {
    return setting("mcp.resourceIdField", "MCP_RESOURCE_ID_FIELD", "resourceId");
  }

  static JsonObject configuration() {
    return new JsonObject()
        .put("mcp.port", integerSetting("mcp.port", "MCP_PORT", 3001))
        .put("mcp.host", setting("mcp.host", "MCP_HOST", "127.0.0.1"))
        .put("mcp.basePath", setting("mcp.basePath", "MCP_BASE_PATH", ""))
        .put("mcp.allowedOrigins", setting(
            "mcp.allowedOrigins", "MCP_ALLOWED_ORIGINS", ""))
        .put("mcp.authToken", setting("mcp.authToken", "MCP_AUTH_TOKEN", ""))
        .put("mcp.oauth.enabled", booleanSetting(
            "mcp.oauth.enabled", "MCP_OAUTH_ENABLED", false))
        .put("mcp.oauth.resourceUri", setting(
            "mcp.oauth.resourceUri", "MCP_OAUTH_RESOURCE_URI", ""))
        .put("mcp.oauth.issuer", setting(
            "mcp.oauth.issuer", "MCP_OAUTH_ISSUER", ""))
        .put("mcp.oauth.requiredScopes", setting(
            "mcp.oauth.requiredScopes", "MCP_OAUTH_REQUIRED_SCOPES", ""))
        .put("mcp.oauth.clockSkewSeconds", integerSetting(
            "mcp.oauth.clockSkewSeconds", "MCP_OAUTH_CLOCK_SKEW_SECONDS", 30))
        .put("mcp.maxRequestsPerMinute", integerSetting(
            "mcp.maxRequestsPerMinute", "MCP_MAX_REQUESTS_PER_MINUTE", 120))
        .put("mcp.maxBodyBytes", longSetting(
            "mcp.maxBodyBytes", "MCP_MAX_BODY_BYTES", 1_048_576L))
        .put("mcp.toolTimeoutMs", longSetting(
            "mcp.toolTimeoutMs", "MCP_TOOL_TIMEOUT_MS", 30_000L))
        .put("mcp.validationTimeoutMs", longSetting(
            "mcp.validationTimeoutMs", "MCP_VALIDATION_TIMEOUT_MS", 2_000L))
        .put("mcp.cancellationGraceMs", longSetting(
            "mcp.cancellationGraceMs", "MCP_CANCELLATION_GRACE_MS", 250L))
        .put("mcp.maxConcurrentToolCalls", integerSetting(
            "mcp.maxConcurrentToolCalls", "MCP_MAX_CONCURRENT_TOOL_CALLS", 64))
        .put("mcp.maxConcurrentCallsPerTool", integerSetting(
            "mcp.maxConcurrentCallsPerTool", "MCP_MAX_CONCURRENT_CALLS_PER_TOOL", 16))
        .put("mcp.maxConcurrentValidations", integerSetting(
            "mcp.maxConcurrentValidations", "MCP_MAX_CONCURRENT_VALIDATIONS", 32))
        .put("mcp.maxResponseBytes", longSetting(
            "mcp.maxResponseBytes", "MCP_MAX_RESPONSE_BYTES", 1_048_576L))
        .put("mcp.healthEnabled", booleanSetting(
            "mcp.healthEnabled", "MCP_HEALTH_ENABLED", false))
        .put("mcp.trustedProxies", setting(
            "mcp.trustedProxies", "MCP_TRUSTED_PROXIES", ""))
        .put("mcp.clientAddressHeader", setting(
            "mcp.clientAddressHeader", "MCP_CLIENT_ADDRESS_HEADER", "X-Forwarded-For"));
  }

  static String setting(String property, String environment, String fallback) {
    String propertyValue = System.getProperty(property);
    if (propertyValue != null && !propertyValue.isBlank()) {
      return propertyValue;
    }
    String environmentValue = System.getenv(environment);
    return environmentValue == null || environmentValue.isBlank()
        ? fallback
        : environmentValue;
  }

  static int integerSetting(String property, String environment, int fallback) {
    String value = setting(property, environment, Integer.toString(fallback));
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException error) {
      throw new IllegalArgumentException(
          property + "/" + environment + " must be an integer: " + value,
          error);
    }
  }

  static long longSetting(String property, String environment, long fallback) {
    String value = setting(property, environment, Long.toString(fallback));
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException error) {
      throw new IllegalArgumentException(
          property + "/" + environment + " must be a long integer: " + value,
          error);
    }
  }

  static boolean booleanSetting(String property, String environment, boolean fallback) {
    String value = setting(property, environment, Boolean.toString(fallback));
    if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
      return Boolean.parseBoolean(value);
    }
    throw new IllegalArgumentException(
        property + "/" + environment + " must be true or false: " + value);
  }
}
