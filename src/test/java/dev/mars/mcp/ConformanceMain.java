package dev.mars.mcp;

import dev.mars.mcp.tool.ToolRegistry;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;

/** Test-only server process used by the official MCP conformance harness. */
public final class ConformanceMain {

  private static final Logger LOG = LoggerFactory.getLogger(ConformanceMain.class);

  private ConformanceMain() {}

  public static void main(String[] args) throws InterruptedException {
    Main.configureVertxLogging();
    int port = Main.integerSetting("mcp.port", "MCP_PORT", 3001);
    JsonObject config = Main.configuration()
        .put("mcp.host", "127.0.0.1")
        .put("mcp.port", port)
        .put("mcp.allowedOrigins", "http://127.0.0.1:" + port)
        .put("mcp.maxRequestsPerMinute", 10_000)
        .put("mcp.healthEnabled", true);

    Vertx vertx = Vertx.vertx();
    CountDownLatch stopped = new CountDownLatch(1);
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      LOG.info("Conformance fixture shutdown requested");
      try {
        vertx.close().toCompletionStage().toCompletableFuture().join();
      } finally {
        stopped.countDown();
      }
    }, "mcp-conformance-shutdown"));

    var tools = ToolRegistry.of(ConformanceTools.all());
    String deploymentId = vertx.deployVerticle(
            new McpServerVerticle(tools, Main.resourceIdField()),
            new DeploymentOptions().setConfig(config))
        .toCompletionStage().toCompletableFuture().join();
    LOG.info("Conformance fixture deployed: deploymentId={} port={} tools={}",
        deploymentId, port, tools.size());
    stopped.await();
  }
}
