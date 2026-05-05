package dev.mars.agent.ui;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.eventbus.MessageConsumer;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Interactive workflow runner UI that lets users submit trade failures and
 * watch the pipeline process them in real-time via Server-Sent Events.
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code GET /workflow/} — serves the self-contained HTML page.</li>
 *   <li>{@code POST /workflow/api/run} — accepts a trade failure JSON payload,
 *       dispatches it to the pipeline via the event bus, and returns the
 *       final result.</li>
 *   <li>{@code GET /workflow/api/events?tradeId=X} — opens an SSE stream
 *       that pushes domain events (from {@code events.out}) matching the
 *       given tradeId, plus internal stage-progress events synthesised
 *       by intercepting the request/reply flow.</li>
 * </ul>
 *
 * <h2>Configuration</h2>
 * <ul>
 *   <li>{@code workflow.port} — HTTP port (default 8082)</li>
 * </ul>
 */
public class WorkflowUiVerticle extends AbstractVerticle {

  private static final Logger LOG = Logger.getLogger(WorkflowUiVerticle.class.getName());

  private final String inboundAddress;
  private final String eventsAddress;
  private final long requestTimeoutMs;
  private final int pipelineUiPort;

  /**
   * Active SSE connections keyed by tradeId. Multiple connections for the
   * same tradeId are not expected (single-user demo), but the map is
   * concurrent for safety.
   */
  private final Map<String, HttpServerResponse> sseClients = new ConcurrentHashMap<>();

  private MessageConsumer<JsonObject> eventConsumer;

  /**
   * @param inboundAddress   event bus address to send trade failures to (e.g. "trade.failures")
   * @param eventsAddress    event bus address to subscribe to for domain events (e.g. "events.out")
   * @param requestTimeoutMs timeout for the pipeline request/reply
   * @param pipelineUiPort   port the Pipeline Config UI is running on (e.g. 8081)
   */
  public WorkflowUiVerticle(String inboundAddress, String eventsAddress, long requestTimeoutMs, int pipelineUiPort) {
    this.inboundAddress = inboundAddress;
    this.eventsAddress = eventsAddress;
    this.requestTimeoutMs = requestTimeoutMs;
    this.pipelineUiPort = pipelineUiPort;
  }

  @Override
  public void start(Promise<Void> startPromise) {
    Router router = Router.router(vertx);

    // ── Body handler for POST ─────────────────────────────────────
    router.post("/workflow/api/*").handler(BodyHandler.create());

    // ── Config endpoint ───────────────────────────────────────────
    router.get("/workflow/api/config").handler(ctx ->
        ctx.response()
            .putHeader("content-type", "application/json")
            .putHeader("access-control-allow-origin", "*")
            .end(new JsonObject().put("pipelineUiPort", pipelineUiPort).encode()));

    // ── SSE endpoint for domain events ────────────────────────────
    router.get("/workflow/api/events").handler(ctx -> {
      String tradeId = ctx.request().getParam("tradeId");
      if (tradeId == null || tradeId.isBlank()) {
        ctx.response().setStatusCode(400).end("tradeId required");
        return;
      }

      HttpServerResponse resp = ctx.response();
      resp.setChunked(true);
      resp.putHeader("content-type", "text/event-stream");
      resp.putHeader("cache-control", "no-cache");
      resp.putHeader("connection", "keep-alive");
      resp.putHeader("access-control-allow-origin", "*");

      // send initial comment to establish connection
      resp.write(": connected\n\n");

      sseClients.put(tradeId, resp);
      LOG.info("SSE client connected for tradeId=" + tradeId);

      resp.closeHandler(v -> {
        sseClients.remove(tradeId);
        LOG.info("SSE client disconnected for tradeId=" + tradeId);
      });
    });

    // ── Trade XML endpoint ────────────────────────────────────────
    router.get("/workflow/api/trade/:tradeId").handler(ctx -> {
      String tradeId = ctx.pathParam("tradeId");
      String xmlPath = "trades/" + tradeId + ".xml";
      vertx.fileSystem().readFile(xmlPath)
          .onSuccess(buf -> ctx.response()
              .putHeader("content-type", "application/xml;charset=UTF-8")
              .putHeader("access-control-allow-origin", "*")
              .end(buf))
          .onFailure(err -> ctx.response()
              .setStatusCode(404)
              .putHeader("content-type", "application/json")
              .end(new JsonObject()
                  .put("error", "Trade XML not found: " + tradeId)
                  .encode()));
    });

    // ── Run workflow endpoint ─────────────────────────────────────
    router.post("/workflow/api/run").handler(ctx -> {
      JsonObject payload;
      try {
        payload = ctx.body().asJsonObject();
      } catch (Exception e) {
        ctx.response().setStatusCode(400)
            .putHeader("content-type", "application/json")
            .end(new JsonObject().put("error", "Invalid JSON").encode());
        return;
      }

      String tradeId = payload.getString("tradeId", "<unknown>");
      LOG.info("Workflow run requested: tradeId=" + tradeId + " reason=" + payload.getString("reason"));

      // send stage event: received
      pushStage(tradeId, "received", "Request received by workflow runner");

      DeliveryOptions opts = new DeliveryOptions().setSendTimeout(requestTimeoutMs);

      // send stage event: dispatching
      pushStage(tradeId, "dispatching", "Sending to event bus: " + inboundAddress);

      vertx.eventBus().<JsonObject>request(inboundAddress, payload, opts)
          .onSuccess(reply -> {
            JsonObject result = reply.body();
            String path = result.getString("path", "unknown");
            pushStage(tradeId, "completed", "Pipeline returned: path=" + path);

            ctx.response()
                .putHeader("content-type", "application/json")
                .putHeader("access-control-allow-origin", "*")
                .end(result.encodePrettily());
          })
          .onFailure(err -> {
            LOG.log(Level.WARNING, "Workflow run failed for tradeId=" + tradeId, err);
            pushStage(tradeId, "error", err.getMessage());

            ctx.response()
                .setStatusCode(500)
                .putHeader("content-type", "application/json")
                .putHeader("access-control-allow-origin", "*")
                .end(new JsonObject()
                    .put("status", "error")
                    .put("error", err.getMessage())
                    .encode());
          });
    });

    // ── Serve the HTML page ───────────────────────────────────────
    router.route("/workflow/*").handler(routingCtx -> {
      String path = routingCtx.request().path();
      if (path.equals("/workflow/") || path.equals("/workflow/index.html")
          || path.equals("/workflow/workflow.html")) {
        vertx.fileSystem().readFile("webroot/workflow.html")
            .onSuccess(buf -> routingCtx.response()
                .putHeader("content-type", "text/html;charset=UTF-8")
                .end(buf))
            .onFailure(err -> routingCtx.response().setStatusCode(500).end());
      } else if (path.endsWith(".css")) {
        String fileName = path.substring(path.lastIndexOf('/') + 1);
        vertx.fileSystem().readFile("webroot/" + fileName)
            .onSuccess(buf -> routingCtx.response()
                .putHeader("content-type", "text/css;charset=UTF-8")
                .end(buf))
            .onFailure(err -> routingCtx.response().setStatusCode(404).end());
      } else if (path.endsWith(".js")) {
        String fileName = path.substring(path.lastIndexOf('/') + 1);
        vertx.fileSystem().readFile("webroot/" + fileName)
            .onSuccess(buf -> routingCtx.response()
                .putHeader("content-type", "application/javascript;charset=UTF-8")
                .end(buf))
            .onFailure(err -> routingCtx.response().setStatusCode(404).end());
      } else {
        routingCtx.response().setStatusCode(404).end();
      }
    });
    router.get("/workflow").handler(ctx ->
        ctx.response().putHeader("location", "/workflow/").setStatusCode(302).end());

    // ── Subscribe to domain events on the event bus ───────────────
    eventConsumer = vertx.eventBus().consumer(eventsAddress, msg -> {
      JsonObject event = (JsonObject) msg.body();
      String tradeId = event.getString("tradeId");
      if (tradeId != null) {
        HttpServerResponse resp = sseClients.get(tradeId);
        if (resp != null && !resp.ended()) {
          resp.write("event: domain-event\ndata: " + event.encode() + "\n\n");
        }
      }
      // also broadcast to all SSE clients whose tradeId matches the caseId field
      String caseId = event.getString("caseId");
      if (caseId != null && !caseId.equals(tradeId)) {
        HttpServerResponse resp = sseClients.get(caseId);
        if (resp != null && !resp.ended()) {
          resp.write("event: domain-event\ndata: " + event.encode() + "\n\n");
        }
      }
    });

    // ── Start HTTP server ─────────────────────────────────────────
    int port = config().getInteger("workflow.port", 8082);
    vertx.createHttpServer()
        .requestHandler(router)
        .listen(port)
        .onSuccess(server -> {
          LOG.info("Workflow UI server started on port " + server.actualPort()
              + " — open http://localhost:" + server.actualPort() + "/workflow/");
          startPromise.complete();
        })
        .onFailure(startPromise::fail);
  }

  @Override
  public void stop() {
    if (eventConsumer != null) {
      eventConsumer.unregister();
    }
    sseClients.values().forEach(resp -> {
      if (!resp.ended()) resp.end();
    });
    sseClients.clear();
  }

  /**
   * Push a stage-progress SSE event to the connected client for the
   * given tradeId.
   */
  private void pushStage(String tradeId, String stage, String detail) {
    HttpServerResponse resp = sseClients.get(tradeId);
    if (resp != null && !resp.ended()) {
      JsonObject evt = new JsonObject()
          .put("stage", stage)
          .put("detail", detail)
          .put("timestamp", System.currentTimeMillis());
      resp.write("event: stage\ndata: " + evt.encode() + "\n\n");
    }
  }
}
