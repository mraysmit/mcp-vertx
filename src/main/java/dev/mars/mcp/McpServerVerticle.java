package dev.mars.mcp;

import dev.mars.mcp.tool.ToolContext;
import dev.mars.mcp.tool.Tool;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * MCP (Model Context Protocol) server verticle that exposes registered tools
 * over the <b>Streamable HTTP transport</b> (protocol version 2025-03-26).
 *
 * <p>This implementation also maintains backwards compatibility with the
 * deprecated HTTP+SSE transport from protocol version 2024-11-05.
 *
 * <h2>Streamable HTTP Transport (2025-03-26)</h2>
 * The server provides a single MCP endpoint that supports:
 * <ul>
 *   <li>{@code POST <basePath>/mcp} — JSON-RPC requests. The server responds
 *       with either {@code application/json} or {@code text/event-stream}
 *       based on the client's {@code Accept} header.</li>
 *   <li>{@code GET <basePath>/mcp} — Opens an SSE stream for server-initiated
 *       messages (optional).</li>
 *   <li>{@code DELETE <basePath>/mcp} — Terminates the session.</li>
 * </ul>
 *
 * <h2>Session Management</h2>
 * Sessions are managed via the {@code Mcp-Session-Id} header:
 * <ul>
 *   <li>Server assigns a session ID in the {@code InitializeResult} response</li>
 *   <li>Client must include {@code Mcp-Session-Id} header on subsequent requests</li>
 *   <li>Invalid/expired session IDs result in HTTP 404</li>
 * </ul>
 *
 * <h2>Backwards Compatibility (2024-11-05)</h2>
 * The server also hosts the deprecated transport:
 * <ul>
 *   <li>{@code GET <basePath>/sse} — SSE connection with {@code endpoint} event</li>
 *   <li>{@code POST <basePath>/message?sessionId=<id>} — Legacy message endpoint</li>
 * </ul>
 *
 * <h2>Supported JSON-RPC methods</h2>
 * <table>
 *   <tr><th>Method</th><th>Description</th></tr>
 *   <tr><td>{@code initialize}</td><td>Capability negotiation handshake</td></tr>
 *   <tr><td>{@code ping}</td><td>Health check (returns empty result)</td></tr>
 *   <tr><td>{@code tools/list}</td><td>Returns all registered tools with
 *       name, description, and inputSchema</td></tr>
 *   <tr><td>{@code tools/call}</td><td>Invokes a tool by name with the
 *       supplied arguments</td></tr>
 * </table>
 *
 * <h2>JSON-RPC Batching</h2>
 * The server supports JSON-RPC batch requests as per the 2025-03-26 spec.
 * Clients can send an array of requests/notifications and receive batched responses.
 *
 * <h2>Configuration (Vert.x config)</h2>
 * <ul>
 *   <li>{@code mcp.port} — TCP port for the MCP HTTP server
 *       (default {@code 3001})</li>
 *   <li>{@code mcp.basePath} — URL path prefix for MCP endpoints
 *       (default {@code ""})</li>
 * </ul>
 *
 * <h2>Tool invocation context</h2>
 * Every tool call receives a lightweight {@link ToolContext} with a generated
 * correlation ID and an optional resource ID derived from the tool arguments.
 *
 * @see Tool
 * @see dev.mars.mcp.tool.ToolRegistry
 */
public class McpServerVerticle extends AbstractVerticle {

  private static final Logger LOG = Logger.getLogger(McpServerVerticle.class.getName());

  /** MCP protocol version supported by this server (2025-03-26). */
  static final String PROTOCOL_VERSION = "2025-03-26";
  /** Legacy protocol version for backwards compatibility. */
  static final String LEGACY_PROTOCOL_VERSION = "2024-11-05";
  static final String SERVER_NAME = "mcp-vertx";
  static final String SERVER_VERSION = "0.2.0";

  /** Header name for MCP session ID (2025-03-26 spec). */
  static final String SESSION_ID_HEADER = "Mcp-Session-Id";

  // JSON-RPC 2.0 error codes
  private static final int ERR_PARSE_ERROR       = -32700;
  private static final int ERR_INVALID_REQUEST   = -32600;
  private static final int ERR_METHOD_NOT_FOUND  = -32601;
  private static final int ERR_INVALID_PARAMS    = -32602;
  private static final int ERR_INTERNAL          = -32603;

  private static final int DEFAULT_MCP_PORT = 3001;

  private final Map<String, Tool> tools;
  private final String resourceIdField;

  /** Active sessions: sessionId → session data. */
  private final Map<String, Session> sessions = new ConcurrentHashMap<>();

  /** Legacy SSE sessions for backwards compatibility: sessionId → response. */
  private final Map<String, HttpServerResponse> legacySseSessions = new ConcurrentHashMap<>();

  /** Actual TCP port after successful listen (−1 until started). */
  private volatile int actualPort = -1;

  /**
   * Creates a new MCP server verticle.
   *
   * @param tools           the allow-listed tool map (name → tool) to expose
   * @param resourceIdField the argument field used as the invocation resource ID
   */
  public McpServerVerticle(Map<String, Tool> tools, String resourceIdField) {
    this.tools = Map.copyOf(tools);
    this.resourceIdField = resourceIdField;
  }

  /** Creates an MCP server with the supplied tools and a default resource ID field. */
  public McpServerVerticle(Map<String, Tool> tools) {
    this(tools, "resourceId");
  }

  /** Creates an MCP server with no registered tools. */
  public McpServerVerticle() {
    this(Map.of());
  }

  /**
   * Returns the actual TCP port after successful start, or {@code −1}
   * if the server has not started yet.
   */
  int actualPort() { return actualPort; }

  @Override
  public void start(Promise<Void> startPromise) {
    int port = config().getInteger("mcp.port", DEFAULT_MCP_PORT);
    String basePath = config().getString("mcp.basePath", "");

    Router router = Router.router(vertx);
    router.route().handler(BodyHandler.create());

    // ── Streamable HTTP transport (2025-03-26) ──
    router.post(basePath + "/mcp").handler(this::handleMcpPost);
    router.get(basePath + "/mcp").handler(this::handleMcpGet);
    router.delete(basePath + "/mcp").handler(this::handleMcpDelete);

    // ── Legacy HTTP+SSE transport (2024-11-05) for backwards compatibility ──
    router.get(basePath + "/sse").handler(this::handleLegacySse);
    router.post(basePath + "/message").handler(this::handleLegacyMessage);

    vertx.createHttpServer()
      .requestHandler(router)
      .listen(port)
      .onSuccess(server -> {
        actualPort = server.actualPort();
        LOG.info("MCP server started on port " + server.actualPort()
            + " (basePath=\"" + basePath + "\", protocol=" + PROTOCOL_VERSION + ")");
        startPromise.complete();
      })
      .onFailure(startPromise::fail);
  }

  // ══════════════════════════════════════════════════════════════════
  // Streamable HTTP Transport (2025-03-26)
  // ══════════════════════════════════════════════════════════════════

  /**
   * Handles POST requests to the MCP endpoint.
   * Supports single requests, notifications, and batched requests.
   */
  private void handleMcpPost(RoutingContext ctx) {
    HttpServerRequest req = ctx.request();
    HttpServerResponse resp = ctx.response();

    // Validate Accept header
    String accept = req.getHeader("Accept");
    if (accept == null || (!accept.contains("application/json") && !accept.contains("text/event-stream"))) {
      LOG.warning("MCP POST missing valid Accept header");
      resp.setStatusCode(400)
        .putHeader("Content-Type", "application/json")
        .end(jsonRpcError(null, ERR_INVALID_REQUEST, "Accept header must include application/json or text/event-stream").encode());
      return;
    }

    // Parse body
    String bodyStr = ctx.body().asString();
    if (bodyStr == null || bodyStr.isBlank()) {
      resp.setStatusCode(400)
        .putHeader("Content-Type", "application/json")
        .end(jsonRpcError(null, ERR_PARSE_ERROR, "Empty request body").encode());
      return;
    }

    // Determine if batch or single request
    bodyStr = bodyStr.trim();
    boolean isBatch = bodyStr.startsWith("[");

    if (isBatch) {
      handleBatchRequest(ctx, bodyStr, accept);
    } else {
      handleSingleRequest(ctx, bodyStr, accept);
    }
  }

  /**
   * Handles a single JSON-RPC request.
   */
  private void handleSingleRequest(RoutingContext ctx, String bodyStr, String accept) {
    HttpServerRequest req = ctx.request();
    HttpServerResponse resp = ctx.response();

    JsonObject request;
    try {
      request = new JsonObject(bodyStr);
    } catch (Exception e) {
      resp.setStatusCode(400)
        .putHeader("Content-Type", "application/json")
        .end(jsonRpcError(null, ERR_PARSE_ERROR, "Invalid JSON: " + e.getMessage()).encode());
      return;
    }

    String method = request.getString("method", "");
    Object id = request.getValue("id");
    JsonObject params = request.getJsonObject("params", new JsonObject());

    // Notifications (no id) — acknowledge without response
    if (id == null) {
      LOG.fine("MCP notification received: " + method);
      resp.setStatusCode(202).end();
      return;
    }

    // Check session for non-initialize requests
    String sessionId = req.getHeader(SESSION_ID_HEADER);
    if (!"initialize".equals(method)) {
      if (sessionId == null || !sessions.containsKey(sessionId)) {
        LOG.warning("MCP request without valid session: method=" + method + " sessionId=" + sessionId);
        if (sessionId != null && !sessions.containsKey(sessionId)) {
          // Session expired or invalid → 404
          resp.setStatusCode(404)
            .putHeader("Content-Type", "application/json")
            .end(jsonRpcError(id, ERR_INVALID_REQUEST, "Session not found or expired").encode());
        } else {
          resp.setStatusCode(400)
            .putHeader("Content-Type", "application/json")
            .end(jsonRpcError(id, ERR_INVALID_REQUEST, "Missing " + SESSION_ID_HEADER + " header").encode());
        }
        return;
      }
    }

    LOG.info("MCP request: method=" + method + " id=" + id);
    boolean prefersSse = accept.contains("text/event-stream");

    dispatch(method, params)
      .onSuccess(result -> {
        JsonObject rpcResponse = new JsonObject()
          .put("jsonrpc", "2.0")
          .put("id", id)
          .put("result", result);

        // For initialize, create session and include session ID in response header
        if ("initialize".equals(method)) {
          String newSessionId = UUID.randomUUID().toString();
          sessions.put(newSessionId, new Session(newSessionId));
          resp.putHeader(SESSION_ID_HEADER, newSessionId);
          LOG.info("MCP session created: " + newSessionId);
        }

        if (prefersSse) {
          sendSseResponse(resp, rpcResponse);
        } else {
          sendJsonResponse(resp, rpcResponse);
        }
      })
      .onFailure(err -> {
        int code = errorCodeFor(err);
        LOG.warning("MCP error for method=" + method + " id=" + id + ": " + err.getMessage());
        JsonObject rpcError = jsonRpcError(id, code, err.getMessage());

        if (prefersSse) {
          sendSseResponse(resp, rpcError);
        } else {
          sendJsonResponse(resp, rpcError);
        }
      });
  }

  /**
   * Handles a batch of JSON-RPC requests.
   */
  private void handleBatchRequest(RoutingContext ctx, String bodyStr, String accept) {
    HttpServerRequest req = ctx.request();
    HttpServerResponse resp = ctx.response();

    JsonArray batch;
    try {
      batch = new JsonArray(bodyStr);
    } catch (Exception e) {
      resp.setStatusCode(400)
        .putHeader("Content-Type", "application/json")
        .end(jsonRpcError(null, ERR_PARSE_ERROR, "Invalid JSON array: " + e.getMessage()).encode());
      return;
    }

    if (batch.isEmpty()) {
      resp.setStatusCode(400)
        .putHeader("Content-Type", "application/json")
        .end(jsonRpcError(null, ERR_INVALID_REQUEST, "Empty batch").encode());
      return;
    }

    String sessionId = req.getHeader(SESSION_ID_HEADER);
    List<Future<JsonObject>> futures = new ArrayList<>();
    List<Boolean> isNotification = new ArrayList<>();

    for (int i = 0; i < batch.size(); i++) {
      JsonObject request = batch.getJsonObject(i);
      String method = request.getString("method", "");
      Object id = request.getValue("id");
      JsonObject params = request.getJsonObject("params", new JsonObject());

      if (id == null) {
        // Notification
        isNotification.add(true);
        futures.add(Future.succeededFuture(null));
        LOG.fine("MCP batch notification: " + method);
      } else {
        isNotification.add(false);

        // Session validation for non-initialize
        if (!"initialize".equals(method) && (sessionId == null || !sessions.containsKey(sessionId))) {
          futures.add(Future.succeededFuture(jsonRpcError(id, ERR_INVALID_REQUEST, "Invalid session")));
        } else {
          int idx = i;
          futures.add(dispatch(method, params)
            .map(result -> {
              JsonObject rpcResp = new JsonObject()
                .put("jsonrpc", "2.0")
                .put("id", id)
                .put("result", result);
              return rpcResp;
            })
            .recover(err -> Future.succeededFuture(jsonRpcError(id, errorCodeFor(err), err.getMessage()))));
        }
      }
    }

    Future.all(futures).onComplete(ar -> {
      JsonArray responses = new JsonArray();
      boolean hasRequests = false;
      String newSessionId = null;

      for (int i = 0; i < futures.size(); i++) {
        if (!isNotification.get(i)) {
          JsonObject result = futures.get(i).result();
          if (result != null) {
            responses.add(result);
            hasRequests = true;

            // Check if this was initialize
            JsonObject request = batch.getJsonObject(i);
            if ("initialize".equals(request.getString("method")) && result.containsKey("result")) {
              newSessionId = UUID.randomUUID().toString();
              sessions.put(newSessionId, new Session(newSessionId));
            }
          }
        }
      }

      if (newSessionId != null) {
        resp.putHeader(SESSION_ID_HEADER, newSessionId);
      }

      if (!hasRequests) {
        // All notifications
        resp.setStatusCode(202).end();
      } else if (accept.contains("text/event-stream")) {
        sendSseResponse(resp, responses);
      } else {
        sendJsonResponse(resp, responses);
      }
    });
  }

  /**
   * Handles GET requests to the MCP endpoint (server-initiated SSE stream).
   */
  private void handleMcpGet(RoutingContext ctx) {
    HttpServerRequest req = ctx.request();
    HttpServerResponse resp = ctx.response();

    String accept = req.getHeader("Accept");
    if (accept == null || !accept.contains("text/event-stream")) {
      resp.setStatusCode(405)
        .putHeader("Content-Type", "application/json")
        .end(new JsonObject().put("error", "Method Not Allowed").encode());
      return;
    }

    String sessionId = req.getHeader(SESSION_ID_HEADER);
    if (sessionId == null || !sessions.containsKey(sessionId)) {
      resp.setStatusCode(sessionId == null ? 400 : 404)
        .putHeader("Content-Type", "application/json")
        .end(new JsonObject().put("error", "Invalid or missing session").encode());
      return;
    }

    // Open SSE stream for server-to-client messages
    resp.setChunked(true)
      .putHeader("Content-Type", "text/event-stream")
      .putHeader("Cache-Control", "no-cache")
      .putHeader("Connection", "keep-alive")
      .putHeader("Access-Control-Allow-Origin", "*");

    Session session = sessions.get(sessionId);
    session.sseResponse = resp;

    resp.closeHandler(v -> {
      if (session.sseResponse == resp) {
        session.sseResponse = null;
      }
      LOG.fine("MCP SSE stream closed for session: " + sessionId);
    });

    LOG.info("MCP SSE stream opened for session: " + sessionId);
  }

  /**
   * Handles DELETE requests to terminate a session.
   */
  private void handleMcpDelete(RoutingContext ctx) {
    HttpServerRequest req = ctx.request();
    HttpServerResponse resp = ctx.response();

    String sessionId = req.getHeader(SESSION_ID_HEADER);
    if (sessionId == null) {
      resp.setStatusCode(400)
        .putHeader("Content-Type", "application/json")
        .end(new JsonObject().put("error", "Missing " + SESSION_ID_HEADER + " header").encode());
      return;
    }

    Session session = sessions.remove(sessionId);
    if (session != null) {
      if (session.sseResponse != null) {
        session.sseResponse.end();
      }
      LOG.info("MCP session terminated: " + sessionId);
      resp.setStatusCode(204).end();
    } else {
      resp.setStatusCode(404)
        .putHeader("Content-Type", "application/json")
        .end(new JsonObject().put("error", "Session not found").encode());
    }
  }

  private void sendJsonResponse(HttpServerResponse resp, Object data) {
    String json = data instanceof JsonObject ? ((JsonObject) data).encode() : ((JsonArray) data).encode();
    resp.setStatusCode(200)
      .putHeader("Content-Type", "application/json")
      .end(json);
  }

  private void sendSseResponse(HttpServerResponse resp, Object data) {
    String json = data instanceof JsonObject ? ((JsonObject) data).encode() : ((JsonArray) data).encode();
    resp.setStatusCode(200)
      .putHeader("Content-Type", "text/event-stream")
      .putHeader("Cache-Control", "no-cache")
      .end("event: message\ndata: " + json + "\n\n");
  }

  // ══════════════════════════════════════════════════════════════════
  // Legacy HTTP+SSE Transport (2024-11-05) — Backwards Compatibility
  // ══════════════════════════════════════════════════════════════════

  private void handleLegacySse(RoutingContext ctx) {
    String sessionId = UUID.randomUUID().toString();
    HttpServerResponse response = ctx.response();

    response.setChunked(true)
      .putHeader("Content-Type", "text/event-stream")
      .putHeader("Cache-Control", "no-cache")
      .putHeader("Connection", "keep-alive")
      .putHeader("Access-Control-Allow-Origin", "*");

    legacySseSessions.put(sessionId, response);
    response.closeHandler(v -> {
      legacySseSessions.remove(sessionId);
      LOG.fine("Legacy MCP session closed: " + sessionId);
    });

    // Tell the client where to POST JSON-RPC messages
    String basePath = config().getString("mcp.basePath", "");
    String messageUrl = basePath + "/message?sessionId=" + sessionId;
    response.write("event: endpoint\ndata: " + messageUrl + "\n\n");

    LOG.info("Legacy MCP session opened: " + sessionId);
  }

  private void handleLegacyMessage(RoutingContext ctx) {
    String sessionId = ctx.request().getParam("sessionId");
    HttpServerResponse sseResponse = sessionId != null ? legacySseSessions.get(sessionId) : null;

    if (sseResponse == null) {
      LOG.warning("Invalid or missing sessionId: " + sessionId);
      ctx.response().setStatusCode(400)
        .putHeader("Content-Type", "application/json")
        .end(new JsonObject().put("error", "Invalid or missing sessionId").encode());
      return;
    }

    JsonObject request = ctx.body().asJsonObject();
    if (request == null) {
      ctx.response().setStatusCode(400)
        .putHeader("Content-Type", "application/json")
        .end(new JsonObject().put("error", "Expected JSON body").encode());
      return;
    }

    String method = request.getString("method", "");
    Object id = request.getValue("id");
    JsonObject params = request.getJsonObject("params", new JsonObject());

    // JSON-RPC notifications (no id) — acknowledge without response
    if (id == null) {
      LOG.fine("Legacy MCP notification received: " + method);
      ctx.response().setStatusCode(202).end();
      return;
    }

    // Dispatch the JSON-RPC request
    LOG.info("Legacy MCP request: method=" + method + " id=" + id + " sessionId=" + sessionId);
    dispatchLegacy(method, params)
      .onSuccess(result -> {
        LOG.fine("Legacy MCP response sent for method=" + method + " id=" + id);
        JsonObject rpcResponse = new JsonObject()
          .put("jsonrpc", "2.0")
          .put("id", id)
          .put("result", result);
        sseResponse.write("event: message\ndata: " + rpcResponse.encode() + "\n\n");
        ctx.response().setStatusCode(202).end();
      })
      .onFailure(err -> {
        int code = errorCodeFor(err);
        LOG.warning("Legacy MCP error for method=" + method + " id=" + id + ": " + err.getMessage());
        JsonObject rpcError = new JsonObject()
          .put("jsonrpc", "2.0")
          .put("id", id)
          .put("error", new JsonObject()
            .put("code", code)
            .put("message", err.getMessage()));
        sseResponse.write("event: message\ndata: " + rpcError.encode() + "\n\n");
        ctx.response().setStatusCode(202).end();
      });
  }

  // ══════════════════════════════════════════════════════════════════
  // Method dispatch
  // ══════════════════════════════════════════════════════════════════

  private Future<JsonObject> dispatch(String method, JsonObject params) {
    return switch (method) {
      case "initialize"  -> {
        LOG.info("MCP initialize handshake: protocolVersion=" + PROTOCOL_VERSION);
        yield handleInitialize();
      }
      case "ping"        -> {
        LOG.fine("MCP ping received");
        yield Future.succeededFuture(new JsonObject());
      }
      case "tools/list"  -> {
        LOG.info("MCP tools/list: returning " + tools.size() + " tool(s)");
        yield handleToolsList();
      }
      case "tools/call"  -> handleToolsCall(params);
      default            -> {
        LOG.warning("MCP unknown method: " + method);
        yield Future.failedFuture(
            new MethodNotFoundException("Method not found: " + method));
      }
    };
  }

  /** Legacy dispatch for backwards compatibility (returns 2024-11-05 protocol version). */
  private Future<JsonObject> dispatchLegacy(String method, JsonObject params) {
    return switch (method) {
      case "initialize"  -> {
        LOG.info("Legacy MCP initialize handshake: protocolVersion=" + LEGACY_PROTOCOL_VERSION);
        yield handleInitializeLegacy();
      }
      case "ping"        -> {
        LOG.fine("Legacy MCP ping received");
        yield Future.succeededFuture(new JsonObject());
      }
      case "tools/list"  -> {
        LOG.info("Legacy MCP tools/list: returning " + tools.size() + " tool(s)");
        yield handleToolsList();
      }
      case "tools/call"  -> handleToolsCall(params);
      default            -> {
        LOG.warning("Legacy MCP unknown method: " + method);
        yield Future.failedFuture(
            new MethodNotFoundException("Method not found: " + method));
      }
    };
  }

  private Future<JsonObject> handleInitialize() {
    return Future.succeededFuture(new JsonObject()
      .put("protocolVersion", PROTOCOL_VERSION)
      .put("serverInfo", new JsonObject()
        .put("name", SERVER_NAME)
        .put("version", SERVER_VERSION))
      .put("capabilities", new JsonObject()
        .put("tools", new JsonObject().put("listChanged", false))));
  }

  private Future<JsonObject> handleInitializeLegacy() {
    return Future.succeededFuture(new JsonObject()
      .put("protocolVersion", LEGACY_PROTOCOL_VERSION)
      .put("serverInfo", new JsonObject()
        .put("name", SERVER_NAME)
        .put("version", SERVER_VERSION))
      .put("capabilities", new JsonObject()
        .put("tools", new JsonObject().put("listChanged", false))));
  }

  private Future<JsonObject> handleToolsList() {
    JsonArray toolList = new JsonArray();
    for (Tool tool : tools.values()) {
      toolList.add(new JsonObject()
        .put("name", tool.name())
        .put("description", tool.description())
        .put("inputSchema", tool.schema()));
    }
    return Future.succeededFuture(new JsonObject().put("tools", toolList));
  }

  private Future<JsonObject> handleToolsCall(JsonObject params) {
    String toolName = params.getString("name");
    if (toolName == null || toolName.isBlank()) {
      LOG.warning("MCP tools/call: missing required parameter 'name'");
      return Future.failedFuture(
          new InvalidParamsException("Missing required parameter: name"));
    }

    Tool tool = tools.get(toolName);
    if (tool == null) {
      LOG.warning("MCP tool not found: " + toolName + " (available: " + tools.keySet() + ")");
      return Future.failedFuture(
          new InvalidParamsException(
              "Tool not found: " + toolName + " (not allowlisted)"));
    }

    JsonObject args = params.getJsonObject("arguments", new JsonObject());
    String correlationId = UUID.randomUUID().toString();
    String resourceId = args.getString(resourceIdField, "mcp-" + correlationId);
    ToolContext ctx = new ToolContext(correlationId, resourceId, new JsonObject());

    LOG.info("MCP tools/call: tool=" + toolName + " resourceId=" + resourceId);

    return tool.invoke(args, ctx)
      .map(result -> new JsonObject()
        .put("content", new JsonArray()
          .add(new JsonObject()
            .put("type", "text")
            .put("text", result.encodePrettily()))));
  }

  // ══════════════════════════════════════════════════════════════════
  // Error handling and utilities
  // ══════════════════════════════════════════════════════════════════

  private JsonObject jsonRpcError(Object id, int code, String message) {
    JsonObject error = new JsonObject()
      .put("jsonrpc", "2.0")
      .put("error", new JsonObject()
        .put("code", code)
        .put("message", message));
    if (id != null) {
      error.put("id", id);
    }
    return error;
  }

  private int errorCodeFor(Throwable err) {
    if (err instanceof MethodNotFoundException) return ERR_METHOD_NOT_FOUND;
    if (err instanceof InvalidParamsException)  return ERR_INVALID_PARAMS;
    return ERR_INTERNAL;
  }

  /** Marker exception for JSON-RPC "method not found" errors. */
  static final class MethodNotFoundException extends RuntimeException {
    MethodNotFoundException(String msg) { super(msg); }
  }

  /** Marker exception for JSON-RPC "invalid params" errors. */
  static final class InvalidParamsException extends RuntimeException {
    InvalidParamsException(String msg) { super(msg); }
  }

  /** Session data for Streamable HTTP transport. */
  private static final class Session {
    final String id;
    HttpServerResponse sseResponse;

    Session(String id) {
      this.id = id;
    }
  }
}
