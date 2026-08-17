package dev.mars.mcp;

import dev.mars.a2a.A2aAgent;
import dev.mars.a2a.A2aServerVerticle;
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

    List<A2aAgent> discoveredAgents = new ArrayList<>();
    ServiceLoader.load(A2aAgent.class).forEach(discoveredAgents::add);
    LOG.atInfo().log(() -> "Discovered " + discoveredAgents.size() + " A2A agent provider(s)");

    String resourceIdField = resourceIdField();
    JsonObject config = configuration();
    A2aAgent a2aAgent = selectA2aAgent(config, discoveredAgents);
    LOG.atDebug().log(() -> "Resolved MCP configuration: host=" + config.getString("mcp.host")
        + " port=" + config.getInteger("mcp.port")
        + " basePath=\"" + config.getString("mcp.basePath") + "\""
        + " authConfigured=" + !config.getString("mcp.authToken").isBlank()
        + " oauthEnabled=" + config.getBoolean("mcp.oauth.enabled")
        + " healthEnabled=" + config.getBoolean("mcp.healthEnabled")
        + " a2aEnabled=" + config.getBoolean("a2a.enabled")
        + " a2aHost=" + config.getString("a2a.host")
        + " a2aPort=" + config.getInteger("a2a.port")
        + " a2aBasePath=\"" + config.getString("a2a.basePath") + "\""
        + " a2aAuthConfigured=" + !config.getString("a2a.authToken").isBlank()
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
    var mcpDeployment = vertx.deployVerticle(
        new McpServerVerticle(tools, resourceIdField),
        new DeploymentOptions().setConfig(config));
    mcpDeployment.onSuccess(id -> LOG.info("MCP server deployed: deploymentId=" + id
        + " tools=" + tools.size()));
    mcpDeployment.compose(mcpId -> {
      if (a2aAgent == null) return io.vertx.core.Future.succeededFuture(mcpId);
      LOG.atDebug().log(() -> "Deploying A2A server verticle for agent=\""
          + a2aAgent.agentCard().name() + "\"");
      return vertx.deployVerticle(new A2aServerVerticle(a2aAgent),
              new DeploymentOptions().setConfig(config))
          .onSuccess(a2aId -> LOG.info("A2A server deployed: deploymentId=" + a2aId
              + " agent=\"" + a2aAgent.agentCard().name() + "\""))
          .recover(error -> vertx.undeploy(mcpId)
              .onFailure(cleanup -> LOG.warn(
                  "Unable to undeploy MCP after A2A startup failure", cleanup))
              .compose(ignored -> io.vertx.core.Future.failedFuture(error),
                  cleanup -> io.vertx.core.Future.failedFuture(error)))
          .map(ignored -> mcpId);
    })
      .onFailure(error -> {
        LOG.error("Unable to start server runtime", error);
        vertx.close()
            .onSuccess(ignored -> LOG.debug("Vert.x runtime closed after startup failure"))
            .onFailure(closeError -> LOG.warn(
                "Vert.x close failed after startup failure", closeError))
            .onComplete(ignored -> System.exit(1));
      });
  }

  static A2aAgent selectA2aAgent(JsonObject config, List<A2aAgent> agents) {
    if (!config.getBoolean("a2a.enabled", false)) return null;
    if (agents.size() != 1) {
      throw new IllegalStateException("a2a.enabled requires exactly one "
          + A2aAgent.class.getName() + " service provider; discovered " + agents.size());
    }
    return agents.getFirst();
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
            "mcp.clientAddressHeader", "MCP_CLIENT_ADDRESS_HEADER", "X-Forwarded-For"))
        .put("a2a.enabled", booleanSetting("a2a.enabled", "A2A_ENABLED", false))
        .put("a2a.port", integerSetting("a2a.port", "A2A_PORT", 3002))
        .put("a2a.host", setting("a2a.host", "A2A_HOST", "127.0.0.1"))
        .put("a2a.basePath", setting("a2a.basePath", "A2A_BASE_PATH", "/a2a"))
        .put("a2a.authToken", setting("a2a.authToken", "A2A_AUTH_TOKEN", ""))
        .put("a2a.maxBodyBytes", longSetting(
            "a2a.maxBodyBytes", "A2A_MAX_BODY_BYTES", 1_048_576L));
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
