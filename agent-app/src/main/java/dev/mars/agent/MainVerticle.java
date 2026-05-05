package dev.mars.agent;

import dev.mars.agent.api.HttpApiVerticle;
import dev.mars.agent.config.HandlerFactory;
import dev.mars.agent.config.LlmClientFactory;
import dev.mars.agent.config.PipelineConfig;
import dev.mars.agent.config.PipelineConfigLoader;
import dev.mars.agent.config.McpConfig;
import dev.mars.agent.config.ToolFactory;
import dev.mars.agent.event.EventSinkVerticle;
import dev.mars.agent.llm.LlmClient;
import dev.mars.mcp.McpServerVerticle;
import dev.mars.agent.memory.InMemoryMemoryStore;
import dev.mars.agent.memory.MemoryStore;
import dev.mars.agent.processor.DeterministicFailureProcessorVerticle;
import dev.mars.agent.processor.FailureHandler;
import dev.mars.agent.runner.AgentRunnerVerticle;
import dev.mars.agent.ui.PipelineUiVerticle;
import dev.mars.agent.ui.WorkflowUiVerticle;
import dev.mars.mcp.tool.Tool;
import dev.mars.mcp.tool.ToolRegistry;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Logger;
import java.util.logging.LogManager;

/**
 * Application bootstrap verticle that loads {@link PipelineConfig} from
 * YAML, resolves all components via factories, and deploys the child
 * verticles in the correct order.
 *
 * <h2>Configuration</h2>
 * All pipeline wiring — event bus addresses, HTTP endpoint, schema
 * validation, handlers, tools, LLM client — is externalised to
 * {@code src/main/resources/pipeline.yaml}. See {@link PipelineConfig}
 * for the full YAML schema.
 *
 * <p>The config file path can be overridden via the system property
 * {@code pipeline.config}:
 * <pre>
 * java -Dpipeline.config=my-config.yaml -jar app.jar
 * </pre>
 *
 * <h2>Factories</h2>
 * Short aliases in YAML (e.g. {@code "lookup-enrich"}, {@code "raise-ticket"},
 * {@code "stub"}) are resolved to concrete classes by:
 * <ul>
 *   <li>{@link HandlerFactory} — deterministic failure handlers</li>
 *   <li>{@link ToolFactory} — agent tools</li>
 *   <li>{@link LlmClientFactory} — LLM client implementations</li>
 * </ul>
 *
 * <h2>Deployment order</h2>
 * Verticles are deployed sequentially so that event-bus consumers are
 * registered before producers start sending messages:
 * <ol>
 *   <li>{@link DeterministicFailureProcessorVerticle}</li>
 *   <li>{@link AgentRunnerVerticle}</li>
 *   <li>{@link EventSinkVerticle}</li>
 *   <li>{@link HttpApiVerticle} (last, so HTTP traffic only arrives once
 *       the pipeline is ready)</li>
 *   <li>{@link McpServerVerticle} (optional, only if MCP is enabled in
 *       config)</li>
 * </ol>
 *
 * @see PipelineConfig
 * @see PipelineConfigLoader
 */
public class MainVerticle extends AbstractVerticle {

  static {
    initLogging();
  }

  private static final Logger LOG = Logger.getLogger(MainVerticle.class.getName());

  private final PipelineConfig pipelineConfig;
  private final MemoryStore memory;

  /**
   * Default constructor: loads configuration from the classpath YAML
   * file and uses an in-memory store.
   */
  public MainVerticle() {
    this(PipelineConfigLoader.load(), new InMemoryMemoryStore());
  }

  /**
   * Full constructor for dependency injection and testing.
   *
   * @param pipelineConfig the pipeline configuration
   * @param memory         the memory store implementation
   */
  public MainVerticle(PipelineConfig pipelineConfig, MemoryStore memory) {
    this.pipelineConfig = pipelineConfig;
    this.memory = memory;
  }

  @Override
  public void start(Promise<Void> startPromise) {
    var cfg = pipelineConfig;
    LOG.info("Initialising pipeline from config: addresses=" + cfg.addresses()
        + ", llm=" + cfg.llm().type()
        + ", handlers=" + cfg.handlers().size()
        + ", tools=" + cfg.tools().size());

    // ── Resolve addresses ───────────────────────────────────────────
    String inbound = cfg.addresses().inbound();
    String agent   = cfg.addresses().agent();
    String events  = cfg.addresses().events();

    // ── Resolve tools via factory (before LLM — OpenAI needs tool schemas) ──
    Tool[] toolArray = cfg.tools().stream()
        .map(tc -> ToolFactory.create(tc.type(), vertx, events))
        .toArray(Tool[]::new);
    var tools = ToolRegistry.of(toolArray);
    LOG.info("Tools resolved: " + tools.keySet());

    // ── Resolve LLM client via factory ──────────────────────────────
    LlmClient llm = LlmClientFactory.create(
        cfg.llm().type(), cfg.llm().params(), vertx, tools.values());
    LOG.info("LLM client resolved: " + llm.getClass().getSimpleName());

    // ── Resolve handlers via factory ──────────────────────────────────────
    Map<String, FailureHandler> failureHandlers = new LinkedHashMap<>();
    for (var hc : cfg.handlers()) {
      failureHandlers.put(hc.reason(),
          HandlerFactory.create(hc.type(), hc.params(), vertx, events));
    }
    LOG.info("Failure handlers resolved: " + failureHandlers.keySet());

    // ── Build Vert.x config for child verticles ─────────────────────
    // YAML values are used as defaults; Vert.x config() overrides take
    // precedence (e.g. test passes http.port=0 to avoid port conflicts)
    McpConfig mcpCfg = cfg.mcp();

    JsonObject childConfig = new JsonObject()
        .put("http.port", cfg.http().port())
        .put("request.timeout.ms", cfg.http().requestTimeoutMs())
        .put("agent.max.steps", cfg.agent().maxSteps())
        .put("agent.timeout.ms", cfg.agent().timeoutMs());
    if (mcpCfg != null) {
      childConfig.put("mcp.port", mcpCfg.port());
      childConfig.put("mcp.basePath", mcpCfg.basePath());
    }
    childConfig.mergeIn(config());
    DeploymentOptions childOpts = new DeploymentOptions().setConfig(childConfig);

    // ── Deploy verticles in order ───────────────────────────────────
    LOG.info("Deploying verticles in sequence...");
    vertx.deployVerticle(
          new DeterministicFailureProcessorVerticle(inbound, agent, failureHandlers), childOpts)
      .compose(id -> {
        LOG.info("DeterministicFailureProcessorVerticle deployed");
        return vertx.deployVerticle(
          new AgentRunnerVerticle(agent, llm, tools, memory, cfg.schema().caseIdField()), childOpts);
      })
      .compose(id -> {
        LOG.info("AgentRunnerVerticle deployed");
        return vertx.deployVerticle(new EventSinkVerticle(events));
      })
      .compose(id -> {
        LOG.info("EventSinkVerticle deployed");
        return vertx.deployVerticle(
          new HttpApiVerticle(cfg.http().route(), inbound,
              cfg.schema().allowedFields(), cfg.schema().requiredFields()), childOpts);
      })
      .compose(id -> {
        if (mcpCfg != null && mcpCfg.enabled()) {
          LOG.info("MCP server enabled — deploying McpServerVerticle on port " + childConfig.getInteger("mcp.port"));
          return vertx.deployVerticle(
              new McpServerVerticle(tools, cfg.schema().caseIdField()), childOpts);
        }
        LOG.info("MCP server not enabled — skipping");
        return Future.succeededFuture(id);
      })
      .compose(id -> {
        LOG.info("Deploying PipelineUiVerticle on port " + childConfig.getInteger("ui.port", 8081));
        return vertx.deployVerticle(new PipelineUiVerticle(cfg), childOpts);
      })
      .compose(id -> {
        LOG.info("Deploying WorkflowUiVerticle on port " + childConfig.getInteger("workflow.port", 8082));
        return vertx.deployVerticle(
            new WorkflowUiVerticle(inbound, events, cfg.http().requestTimeoutMs(),
                childConfig.getInteger("ui.port", 8081)), childOpts);
      })
      .onSuccess(id -> {
        int httpPort      = childConfig.getInteger("http.port", 8080);
        int mcpPort       = childConfig.getInteger("mcp.port", 3001);
        int uiPort        = childConfig.getInteger("ui.port", 8081);
        int workflowPort  = childConfig.getInteger("workflow.port", 8082);
        boolean mcpOn     = mcpCfg != null && mcpCfg.enabled();

        String api    = "http://localhost:" + httpPort + cfg.http().route();
        String health = "http://localhost:" + httpPort + "/health";
        String mcp    = mcpOn ? "http://localhost:" + mcpPort + "/mcp" : "(disabled)";
        String ui       = "http://localhost:" + uiPort + "/pipeline/";
        String workflow = "http://localhost:" + workflowPort + "/workflow/";

        int W = 56; // inner width between ║ chars
        String banner = "\n"
            + line('═', W) + "\n"
            + row("vertx5-mcp — pipeline ready", W) + "\n"
            + mid('═', W) + "\n"
            + row("API      " + api, W) + "\n"
            + row("Health   " + health, W) + "\n"
            + row("MCP      " + mcp, W) + "\n"
            + row("UI       " + ui, W) + "\n"
            + row("Runner   " + workflow, W) + "\n"
            + bot('═', W);
        LOG.info(banner);
        startPromise.complete();
      })
      .onFailure(err -> {
        LOG.severe("Pipeline deployment failed: " + err.getMessage());
        startPromise.fail(err);
      });
  }

  /**
   * Loads {@code logging.properties} from the classpath, ensures the
   * {@code logs/} directory exists, and injects the JVM start timestamp
   * into the {@code FileHandler} pattern so each run produces a
   * distinctly named log file, e.g.
   * {@code logs/vertx-agent-2026-03-02_10-15-30-0.log}.
   *
   * <p>If the properties file is missing or the directory cannot be
   * created, the JVM falls back to its built-in defaults (console only).
   */
  private static void initLogging() {
    try {
      Files.createDirectories(Path.of("logs"));
    } catch (IOException e) {
      System.err.println("WARNING: could not create logs/ directory: " + e.getMessage());
    }
    try (InputStream in = MainVerticle.class.getClassLoader()
            .getResourceAsStream("logging.properties")) {
      if (in != null) {
        // Load properties so we can inject the startup timestamp
        Properties props = new Properties();
        props.load(in);

        String timestamp = LocalDateTime.now()
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        props.setProperty("java.util.logging.FileHandler.pattern",
            "logs/vertx-agent-" + timestamp + "-%g.log");

        // Feed the modified properties to LogManager
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        props.store(out, null);
        LogManager.getLogManager()
            .readConfiguration(new ByteArrayInputStream(out.toByteArray()));
      }
    } catch (IOException e) {
      System.err.println("WARNING: could not load logging.properties: " + e.getMessage());
    }
  }

  /** Right-pad with spaces to align the box border. */
  private static String padTo(int n) {
    return n > 0 ? " ".repeat(n) : "";
  }

  private static String line(char c, int w) {
    return "╔" + String.valueOf(c).repeat(w) + "╗";
  }

  private static String mid(char c, int w) {
    return "╠" + String.valueOf(c).repeat(w) + "╣";
  }

  private static String bot(char c, int w) {
    return "╚" + String.valueOf(c).repeat(w) + "╝";
  }

  private static String row(String text, int w) {
    int pad = w - 2 - text.length(); // 2 for the leading/trailing space
    return "║ " + text + (pad > 0 ? " ".repeat(pad) : "") + " ║";
  }
}
