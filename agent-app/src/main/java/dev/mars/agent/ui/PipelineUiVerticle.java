package dev.mars.agent.ui;

import dev.mars.agent.config.PipelineConfig;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;

import java.util.logging.Logger;

/**
 * Lightweight HTTP verticle that serves the pipeline visualisation UI
 * and a JSON API for the current pipeline configuration.
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code GET /api/pipeline} — returns the pipeline configuration
 *       as JSON.</li>
 *   <li>{@code GET /ui/*} — serves static files from the classpath
 *       {@code webroot/} directory (the HTML visualisation page).</li>
 *   <li>{@code GET /ui} — redirects to {@code /ui/}.</li>
 * </ul>
 *
 * <h2>Configuration</h2>
 * The verticle reads {@code ui.port} from the Vert.x config; it
 * defaults to the same HTTP server port used by the main API by
 * sharing the same config. However, in this implementation it runs
 * on its own port to keep concerns separated.
 */
public class PipelineUiVerticle extends AbstractVerticle {

  private static final Logger LOG = Logger.getLogger(PipelineUiVerticle.class.getName());

  private final PipelineConfig pipelineConfig;

  public PipelineUiVerticle(PipelineConfig pipelineConfig) {
    this.pipelineConfig = pipelineConfig;
  }

  @Override
  public void start(Promise<Void> startPromise) {
    Router router = Router.router(vertx);

    // ── JSON API ────────────────────────────────────────────────────
    router.get("/api/pipeline").handler(ctx -> {
      ctx.response()
        .putHeader("content-type", "application/json")
        .putHeader("access-control-allow-origin", "*")
        .end(toJson(pipelineConfig).encodePrettily());
    });

    // ── Raw YAML source ─────────────────────────────────────────────
    String yamlResource = System.getProperty("pipeline.config", "pipeline.yaml");
    router.get("/api/pipeline/yaml").handler(ctx ->
        vertx.fileSystem().readFile(yamlResource)
            .onSuccess(buf -> ctx.response()
                .putHeader("content-type", "text/plain;charset=UTF-8")
                .putHeader("access-control-allow-origin", "*")
                .putHeader("content-disposition", "inline; filename=\"" + yamlResource + "\"")
                .end(buf))
            .onFailure(err -> ctx.response().setStatusCode(404)
                .end("YAML source not found: " + yamlResource)));

    // ── Static UI ───────────────────────────────────────────────────
    // Self-contained HTML page — serve index.html directly (no StaticHandler)
    router.route("/pipeline/*").handler(ctx -> {
      String path = ctx.request().path();
      if (path.equals("/pipeline/") || path.equals("/pipeline/index.html")) {
        vertx.fileSystem().readFile("webroot/index.html")
          .onSuccess(buf -> ctx.response()
              .putHeader("content-type", "text/html;charset=UTF-8")
              .end(buf))
          .onFailure(err -> ctx.response().setStatusCode(500).end());
      } else if (path.endsWith(".css")) {
        String fileName = path.substring(path.lastIndexOf('/') + 1);
        vertx.fileSystem().readFile("webroot/" + fileName)
          .onSuccess(buf -> ctx.response()
              .putHeader("content-type", "text/css;charset=UTF-8")
              .end(buf))
          .onFailure(err -> ctx.response().setStatusCode(404).end());
      } else if (path.endsWith(".js")) {
        String fileName = path.substring(path.lastIndexOf('/') + 1);
        vertx.fileSystem().readFile("webroot/" + fileName)
          .onSuccess(buf -> ctx.response()
              .putHeader("content-type", "application/javascript;charset=UTF-8")
              .end(buf))
          .onFailure(err -> ctx.response().setStatusCode(404).end());
      } else {
        ctx.response().setStatusCode(404).end();
      }
    });
    router.get("/pipeline").handler(ctx ->
      ctx.response().putHeader("location", "/pipeline/").setStatusCode(302).end());

    int port = config().getInteger("ui.port", 8081);
    vertx.createHttpServer()
      .requestHandler(router)
      .listen(port)
      .onSuccess(server -> {
        LOG.info("Pipeline UI server started on port " + server.actualPort()
            + " — open http://localhost:" + server.actualPort() + "/pipeline");
        startPromise.complete();
      })
      .onFailure(startPromise::fail);
  }

  /** Convert the PipelineConfig into a JsonObject for the UI. */
  private static JsonObject toJson(PipelineConfig cfg) {
    JsonObject json = new JsonObject();

    // addresses
    json.put("addresses", new JsonObject()
        .put("inbound", cfg.addresses().inbound())
        .put("agent", cfg.addresses().agent())
        .put("events", cfg.addresses().events()));

    // http
    json.put("http", new JsonObject()
        .put("port", cfg.http().port())
        .put("route", cfg.http().route())
        .put("requestTimeoutMs", cfg.http().requestTimeoutMs()));

    // schema
    json.put("schema", new JsonObject()
        .put("caseIdField", cfg.schema().caseIdField())
        .put("allowedFields", new JsonArray(cfg.schema().allowedFields().stream().toList()))
        .put("requiredFields", new JsonArray(cfg.schema().requiredFields().stream().toList())));

    // agent
    json.put("agent", new JsonObject()
        .put("maxSteps", cfg.agent().maxSteps())
        .put("timeoutMs", cfg.agent().timeoutMs()));

    // handlers
    JsonArray handlers = new JsonArray();
    for (var h : cfg.handlers()) {
      JsonObject ho = new JsonObject()
          .put("reason", h.reason())
          .put("type", h.type());
      if (h.params() != null && !h.params().isEmpty()) {
        ho.put("params", new JsonObject(new java.util.LinkedHashMap<>(h.params())));
      }
      handlers.add(ho);
    }
    json.put("handlers", handlers);

    // tools
    JsonArray tools = new JsonArray();
    for (var t : cfg.tools()) {
      tools.add(new JsonObject().put("type", t.type()));
    }
    json.put("tools", tools);

    // llm
    JsonObject llm = new JsonObject().put("type", cfg.llm().type());
    if (cfg.llm().params() != null && !cfg.llm().params().isEmpty()) {
      llm.put("params", new JsonObject(new java.util.LinkedHashMap<>(cfg.llm().params())));
    }
    json.put("llm", llm);

    // mcp
    if (cfg.mcp() != null) {
      json.put("mcp", new JsonObject()
          .put("enabled", cfg.mcp().enabled())
          .put("port", cfg.mcp().port())
          .put("basePath", cfg.mcp().basePath()));
    }

    return json;
  }
}
