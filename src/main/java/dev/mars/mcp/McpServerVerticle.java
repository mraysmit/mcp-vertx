package dev.mars.mcp;

import dev.mars.mcp.tool.Tool;
import dev.mars.mcp.tool.ToolContext;
import dev.mars.mcp.tool.ToolSchemaValidator;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;

import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Current-generation MCP server using the Streamable HTTP transport from
 * protocol revision {@value #PROTOCOL_VERSION}.
 *
 * <p>The transport is stateless: every request carries its protocol version,
 * client capabilities, and client identity metadata. Deprecated connection
 * sessions, GET streams, and the legacy HTTP+SSE transport are intentionally
 * not exposed.
 */
public final class McpServerVerticle extends AbstractVerticle {

  private static final Logger LOG = Logger.getLogger(McpServerVerticle.class.getName());
  private static final Pattern TOOL_NAME = Pattern.compile("[A-Za-z0-9_.-]{1,128}");

  static final String PROTOCOL_VERSION = "2026-07-28";
  static final String SERVER_NAME = "mcp-vertx";
  static final String SERVER_VERSION = "0.3.0";
  static final String PROTOCOL_VERSION_HEADER = "MCP-Protocol-Version";
  static final String METHOD_HEADER = "Mcp-Method";
  static final String NAME_HEADER = "Mcp-Name";

  private static final String META_PROTOCOL_VERSION = "io.modelcontextprotocol/protocolVersion";
  private static final String META_CLIENT_INFO = "io.modelcontextprotocol/clientInfo";
  private static final String META_CLIENT_CAPABILITIES = "io.modelcontextprotocol/clientCapabilities";
  private static final String META_SERVER_INFO = "io.modelcontextprotocol/serverInfo";

  private static final int ERR_PARSE_ERROR = -32700;
  private static final int ERR_INVALID_REQUEST = -32600;
  private static final int ERR_METHOD_NOT_FOUND = -32601;
  private static final int ERR_INVALID_PARAMS = -32602;
  private static final int ERR_INTERNAL = -32603;
  private static final int ERR_HEADER_MISMATCH = -32020;
  private static final int ERR_UNSUPPORTED_PROTOCOL = -32022;

  private static final int DEFAULT_MCP_PORT = 3001;
  private static final String DEFAULT_HOST = "127.0.0.1";

  private final Map<String, Tool> tools;
  private final Map<String, JsonObject> toolSchemas;
  private final ToolSchemaValidator schemaValidator;
  private final String resourceIdField;
  private final Map<String, RateWindow> rateWindows = new ConcurrentHashMap<>();

  private volatile int actualPort = -1;
  private volatile ServerSettings settings;
  private long cleanupTimerId = -1;

  public McpServerVerticle(Map<String, Tool> tools, String resourceIdField) {
    TreeMap<String, Tool> sorted = new TreeMap<>(tools);
    TreeMap<String, JsonObject> schemas = new TreeMap<>();
    sorted.forEach((name, tool) -> {
      if (!TOOL_NAME.matcher(name).matches()) {
        throw new IllegalArgumentException(
            "Invalid MCP tool name '" + name + "'; expected 1-128 ASCII letters, digits, '.', '-', or '_'");
      }
      if (!name.equals(tool.name())) {
        throw new IllegalArgumentException("Tool registry key does not match tool name: " + name);
      }
      JsonObject schema = tool.schema();
      if (schema == null) {
        throw new IllegalArgumentException("Tool " + name + " returned a null input schema");
      }
      schemas.put(name, schema.copy());
    });
    this.tools = Map.copyOf(sorted);
    this.toolSchemas = Map.copyOf(schemas);
    this.schemaValidator = new ToolSchemaValidator(this.toolSchemas);
    this.resourceIdField = requireNonBlank(resourceIdField, "resourceIdField");
  }

  public McpServerVerticle(Map<String, Tool> tools) {
    this(tools, "resourceId");
  }

  public McpServerVerticle() {
    this(Map.of());
  }

  int actualPort() {
    return actualPort;
  }

  @Override
  public void start(Promise<Void> startPromise) {
    try {
      settings = ServerSettings.from(config());
    } catch (RuntimeException error) {
      startPromise.fail(error);
      return;
    }

    String endpoint = settings.basePath() + "/mcp";
    Router router = Router.router(vertx);
    router.route().handler(this::enforceTransportSecurity);
    router.options(endpoint).handler(this::handleOptions);
    router.route().handler(BodyHandler.create().setBodyLimit(settings.maxBodyBytes()));
    router.post(endpoint).handler(this::handleMcpPost);
    router.get(endpoint).handler(this::methodNotAllowed);
    router.delete(endpoint).handler(this::methodNotAllowed);
    router.route().failureHandler(this::handleRoutingFailure);

    vertx.createHttpServer()
        .requestHandler(router)
        .listen(settings.port(), settings.host())
        .onSuccess(server -> {
          actualPort = server.actualPort();
          cleanupTimerId = vertx.setPeriodic(120_000, ignored -> cleanupRateWindows());
          LOG.info("MCP server started on " + settings.host() + ":" + actualPort
              + " (endpoint=\"" + endpoint + "\", protocol=" + PROTOCOL_VERSION + ")");
          startPromise.complete();
        })
        .onFailure(startPromise::fail);
  }

  @Override
  public void stop(Promise<Void> stopPromise) {
    if (cleanupTimerId >= 0) {
      vertx.cancelTimer(cleanupTimerId);
    }
    rateWindows.clear();
    stopPromise.complete();
  }

  private void enforceTransportSecurity(RoutingContext ctx) {
    HttpServerRequest request = ctx.request();
    String origin = request.getHeader("Origin");
    if (origin != null && !settings.allowedOrigins().contains(origin)) {
      sendRpcError(ctx.response(), 403, null, ERR_INVALID_REQUEST, "Origin is not allowed", null);
      return;
    }
    if (origin != null) {
      ctx.response()
          .putHeader("Access-Control-Allow-Origin", origin)
          .putHeader("Vary", "Origin");
    }

    if (!"OPTIONS".equals(request.method().name()) && !settings.authToken().isBlank()) {
      String expected = "Bearer " + settings.authToken();
      String supplied = request.getHeader("Authorization");
      if (!constantTimeEquals(expected, supplied)) {
        ctx.response().putHeader("WWW-Authenticate", "Bearer");
        sendRpcError(ctx.response(), 401, null, ERR_INVALID_REQUEST, "Authentication required", null);
        return;
      }
    }

    if (!"OPTIONS".equals(request.method().name())) {
      String client = request.remoteAddress() == null
          ? "unknown"
          : request.remoteAddress().hostAddress();
      if (!rateWindows.computeIfAbsent(client, ignored -> new RateWindow())
          .allow(settings.maxRequestsPerMinute())) {
        ctx.response().putHeader("Retry-After", "60");
        sendRpcError(ctx.response(), 429, null, ERR_INTERNAL, "Request rate limit exceeded", null);
        return;
      }
    }
    ctx.next();
  }

  private void handleOptions(RoutingContext ctx) {
    ctx.response()
        .setStatusCode(204)
        .putHeader("Access-Control-Allow-Methods", "POST, OPTIONS")
        .putHeader("Access-Control-Allow-Headers",
            "Authorization, Content-Type, Accept, MCP-Protocol-Version, Mcp-Method, Mcp-Name")
        .putHeader("Access-Control-Max-Age", "600")
        .end();
  }

  private void methodNotAllowed(RoutingContext ctx) {
    ctx.response().putHeader("Allow", "POST, OPTIONS");
    sendRpcError(ctx.response(), 405, null, ERR_INVALID_REQUEST,
        "The current MCP Streamable HTTP endpoint only accepts POST", null);
  }

  private void handleMcpPost(RoutingContext ctx) {
    HttpServerRequest request = ctx.request();
    HttpServerResponse response = ctx.response();

    String contentType = request.getHeader("Content-Type");
    String mediaType = contentType == null
        ? "" : contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    if (!"application/json".equals(mediaType)) {
      sendRpcError(response, 415, null, ERR_INVALID_REQUEST,
          "Content-Type must be application/json", null);
      return;
    }

    String accept = request.getHeader("Accept");
    String normalizedAccept = accept == null ? "" : accept.toLowerCase(Locale.ROOT);
    if (!normalizedAccept.contains("application/json")
        || !normalizedAccept.contains("text/event-stream")) {
      sendRpcError(response, 406, null, ERR_INVALID_REQUEST,
          "Accept must list application/json and text/event-stream", null);
      return;
    }

    String body = ctx.body().asString();
    if (body == null || body.isBlank()) {
      sendRpcError(response, 400, null, ERR_PARSE_ERROR, "Empty request body", null);
      return;
    }

    Object decoded;
    try {
      decoded = Json.decodeValue(body);
    } catch (RuntimeException error) {
      sendRpcError(response, 400, null, ERR_PARSE_ERROR, "Invalid JSON", null);
      return;
    }

    if (decoded instanceof List<?> || decoded instanceof JsonArray) {
      sendRpcError(response, 400, null, ERR_INVALID_REQUEST,
          "JSON-RPC batches are not supported by protocol " + PROTOCOL_VERSION, null);
      return;
    }

    JsonObject message = asJsonObject(decoded);
    if (message == null) {
      sendRpcError(response, 400, null, ERR_INVALID_REQUEST,
          "JSON-RPC message must be an object", null);
      return;
    }

    ParsedRequest parsed;
    try {
      parsed = parseRequest(message);
      validateModernMetadata(request, parsed);
    } catch (RpcException error) {
      sendRpcError(response, error.httpStatus(), error.id(), error.code(),
          error.getMessage(), error.data());
      return;
    }

    if (!parsed.hasId()) {
      if (!parsed.method().startsWith("notifications/")) {
        sendRpcError(response, 400, null, ERR_INVALID_REQUEST,
            "Method " + parsed.method() + " requires a JSON-RPC id", null);
        return;
      }
      LOG.fine("Accepted MCP extension notification: " + parsed.method());
      response.setStatusCode(202).end();
      return;
    }

    Future<JsonObject> execution;
    try {
      execution = dispatch(parsed, request);
    } catch (RuntimeException error) {
      execution = Future.failedFuture(error);
    }
    execution
        .onSuccess(result -> sendJsonResponse(response, parsed.id(), result))
        .onFailure(error -> {
          RpcException rpcError = error instanceof RpcException re
              ? re
              : new RpcException(ERR_INTERNAL, 500, parsed.id(),
                  "Internal server error", null, error);
          LOG.warning("MCP request failed: method=" + parsed.method()
              + " error=" + rpcError.getMessage());
          sendRpcError(response, rpcError.httpStatus(), parsed.id(), rpcError.code(),
              rpcError.getMessage(), rpcError.data());
        });
  }

  private ParsedRequest parseRequest(JsonObject message) {
    Object rawId = message.getValue("id");
    Object errorId = isValidId(rawId) ? rawId : null;
    if (!"2.0".equals(message.getString("jsonrpc"))) {
      throw invalidRequest(errorId, "jsonrpc must be exactly '2.0'");
    }
    Object rawMethod = message.getValue("method");
    if (!(rawMethod instanceof String method) || method.isBlank()) {
      throw invalidRequest(errorId, "method must be a non-empty string");
    }

    boolean hasId = message.containsKey("id");
    if (hasId && !isValidId(rawId)) {
      throw invalidRequest(null, "id must be a string or integer");
    }

    JsonObject params = new JsonObject();
    if (message.containsKey("params")) {
      params = asJsonObject(message.getValue("params"));
      if (params == null) {
        throw new RpcException(ERR_INVALID_PARAMS, 400, errorId,
            "params must be an object", null);
      }
    }
    return new ParsedRequest(rawId, hasId, method, params);
  }

  private void validateModernMetadata(HttpServerRequest request, ParsedRequest parsed) {
    String headerVersion = request.getHeader(PROTOCOL_VERSION_HEADER);
    JsonObject meta = asJsonObject(parsed.params().getValue("_meta"));
    Object rawBodyVersion = meta == null ? null : meta.getValue(META_PROTOCOL_VERSION);
    String bodyVersion = rawBodyVersion instanceof String value ? value : null;

    if (headerVersion == null || bodyVersion == null) {
      throw headerMismatch(parsed.id(),
          "MCP-Protocol-Version header and params._meta protocolVersion are required strings");
    }
    if (!headerVersion.equals(bodyVersion)) {
      throw headerMismatch(parsed.id(),
          "MCP-Protocol-Version header must match params._meta protocolVersion");
    }
    if (!PROTOCOL_VERSION.equals(headerVersion)) {
      JsonObject data = new JsonObject()
          .put("supported", new JsonArray().add(PROTOCOL_VERSION))
          .put("requested", headerVersion);
      throw new RpcException(ERR_UNSUPPORTED_PROTOCOL, 400, parsed.id(),
          "Unsupported protocol version", data);
    }
    if (asJsonObject(meta.getValue(META_CLIENT_CAPABILITIES)) == null) {
      throw invalidMetadata(parsed.id(),
          "params._meta must contain io.modelcontextprotocol/clientCapabilities");
    }
    Object rawClientInfo = meta.getValue(META_CLIENT_INFO);
    JsonObject clientInfo = asJsonObject(rawClientInfo);
    if (rawClientInfo != null && (clientInfo == null
        || !(clientInfo.getValue("name") instanceof String name) || name.isBlank()
        || !(clientInfo.getValue("version") instanceof String version) || version.isBlank())) {
      throw invalidMetadata(parsed.id(), "clientInfo name and version must be non-empty strings");
    }

    String methodHeader = request.getHeader(METHOD_HEADER);
    if (!parsed.method().equals(methodHeader)) {
      throw headerMismatch(parsed.id(), "Mcp-Method header must match the JSON-RPC method");
    }
    if ("tools/call".equals(parsed.method())) {
      Object rawName = parsed.params().getValue("name");
      String name = rawName instanceof String value ? value : null;
      String headerName = decodeMirroredHeader(request.getHeader(NAME_HEADER), parsed.id(), NAME_HEADER);
      if (name == null || !name.equals(headerName)) {
        throw headerMismatch(parsed.id(),
            "Mcp-Name header must match params.name for tools/call");
      }
    }
  }

  private Future<JsonObject> dispatch(ParsedRequest request, HttpServerRequest httpRequest) {
    return switch (request.method()) {
      case "server/discover" -> Future.succeededFuture(discoverResult());
      case "ping" -> Future.succeededFuture(completeResult());
      case "tools/list" -> Future.succeededFuture(toolsListResult());
      case "tools/call" -> callTool(request.params(), httpRequest);
      default -> Future.failedFuture(new RpcException(
          ERR_METHOD_NOT_FOUND, 404, request.id(),
          "Method not found: " + request.method(), null));
    };
  }

  private JsonObject discoverResult() {
    return completeResult()
        .put("supportedVersions", new JsonArray().add(PROTOCOL_VERSION))
        .put("capabilities", serverCapabilities())
        .put("instructions", "Call tools/list to discover available tools before invoking tools/call.")
        .put("ttlMs", 3_600_000)
        .put("cacheScope", "public");
  }

  private JsonObject toolsListResult() {
    JsonArray toolList = new JsonArray();
    tools.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(entry -> toolList.add(new JsonObject()
            .put("name", entry.getKey())
            .put("description", entry.getValue().description())
            .put("inputSchema", toolSchemas.get(entry.getKey()).copy())));
    return completeResult()
        .put("tools", toolList)
        .put("ttlMs", 60_000)
        .put("cacheScope", "public");
  }

  private Future<JsonObject> callTool(JsonObject params, HttpServerRequest request) {
    Object rawToolName = params.getValue("name");
    String toolName = rawToolName instanceof String value ? value : null;
    if (isBlank(toolName)) {
      return Future.failedFuture(invalidParams("Missing required parameter: name"));
    }
    Tool tool = tools.get(toolName);
    if (tool == null) {
      return Future.failedFuture(invalidParams("Unknown tool: " + toolName));
    }

    JsonObject arguments = new JsonObject();
    if (params.containsKey("arguments")) {
      arguments = asJsonObject(params.getValue("arguments"));
      if (arguments == null) {
        return Future.failedFuture(invalidParams("arguments must be an object"));
      }
    }
    String validationError = schemaValidator.validate(toolName, arguments);
    if (!validationError.isEmpty()) {
      return Future.succeededFuture(failedToolResult(
          new IllegalArgumentException("Invalid tool arguments: " + validationError)));
    }

    String correlationId = UUID.randomUUID().toString();
    Object rawResourceId = arguments.getValue(resourceIdField);
    String resourceId = rawResourceId instanceof String value && !value.isBlank()
        ? value : "mcp-" + correlationId;
    JsonObject requestMeta = asJsonObject(params.getValue("_meta"));
    JsonObject metadata = new JsonObject()
        .put("protocolVersion", PROTOCOL_VERSION)
        .put("remoteAddress", request.remoteAddress() == null
            ? null : request.remoteAddress().hostAddress())
        .put("client", requestMeta == null ? null
            : asJsonObject(requestMeta.getValue(META_CLIENT_INFO)));
    ToolContext context = new ToolContext(correlationId, resourceId, metadata);

    Future<JsonObject> invocation;
    try {
      invocation = tool.invoke(arguments.copy(), context);
      if (invocation == null) {
        invocation = Future.failedFuture("Tool returned a null Future");
      }
    } catch (RuntimeException error) {
      invocation = Future.failedFuture(error);
    }

    LOG.info("MCP tools/call: tool=" + toolName + " correlationId=" + correlationId);
    return invocation
        .timeout(settings.toolTimeoutMs(), TimeUnit.MILLISECONDS)
        .map(this::successfulToolResult)
        .recover(error -> Future.succeededFuture(failedToolResult(error)));
  }

  private JsonObject successfulToolResult(JsonObject result) {
    if (result == null) {
      return failedToolResult(new IllegalStateException("Tool returned no result"));
    }
    String encoded = result.encode();
    if (encoded.getBytes(StandardCharsets.UTF_8).length > settings.maxToolResultBytes()) {
      return failedToolResult(new IllegalStateException("Tool result exceeded the configured size limit"));
    }
    return completeResult()
        .put("content", new JsonArray().add(new JsonObject()
            .put("type", "text")
            .put("text", encoded)))
        .put("structuredContent", result.copy())
        .put("isError", false);
  }

  private JsonObject failedToolResult(Throwable error) {
    String message = error == null || isBlank(error.getMessage())
        ? "Tool execution failed"
        : error.getMessage();
    if (message.length() > 500) {
      message = message.substring(0, 500);
    }
    return completeResult()
        .put("content", new JsonArray().add(new JsonObject()
            .put("type", "text")
            .put("text", message)))
        .put("isError", true);
  }

  private JsonObject completeResult() {
    return new JsonObject()
        .put("resultType", "complete")
        .put("_meta", new JsonObject().put(META_SERVER_INFO, serverInfo()));
  }

  private JsonObject serverCapabilities() {
    return new JsonObject().put("tools", new JsonObject().put("listChanged", false));
  }

  private JsonObject serverInfo() {
    return new JsonObject().put("name", SERVER_NAME).put("version", SERVER_VERSION);
  }

  private void sendJsonResponse(HttpServerResponse response, Object id, JsonObject result) {
    response.setStatusCode(200)
        .putHeader("Content-Type", "application/json; charset=utf-8")
        .end(new JsonObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("result", result)
            .encode());
  }

  private void sendRpcError(HttpServerResponse response, int status, Object id,
                            int code, String message, JsonObject data) {
    JsonObject error = new JsonObject().put("code", code).put("message", message);
    if (data != null) {
      error.put("data", data);
    }
    response.setStatusCode(status)
        .putHeader("Content-Type", "application/json; charset=utf-8")
        .end(new JsonObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("error", error)
            .encode());
  }

  private void handleRoutingFailure(RoutingContext ctx) {
    if (ctx.response().ended()) {
      return;
    }
    int status = ctx.statusCode() >= 400 ? ctx.statusCode() : 500;
    String message = status == 413 ? "Request body exceeds the configured limit" : "HTTP request failed";
    sendRpcError(ctx.response(), status, null,
        status == 413 ? ERR_INVALID_REQUEST : ERR_INTERNAL, message, null);
  }

  private void cleanupRateWindows() {
    long cutoff = System.currentTimeMillis() - 120_000;
    rateWindows.entrySet().removeIf(entry -> entry.getValue().lastSeen() < cutoff);
  }

  private static JsonObject asJsonObject(Object value) {
    if (value instanceof JsonObject object) {
      return object;
    }
    if (value instanceof Map<?, ?> map) {
      JsonObject object = new JsonObject();
      map.forEach((key, item) -> object.put(String.valueOf(key), item));
      return object;
    }
    return null;
  }

  private static boolean isValidId(Object id) {
    return id instanceof String || id instanceof Byte || id instanceof Short
        || id instanceof Integer || id instanceof Long;
  }

  private static boolean constantTimeEquals(String expected, String supplied) {
    if (supplied == null) {
      return false;
    }
    return MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
  }

  private static String decodeMirroredHeader(String value, Object id, String headerName) {
    if (value == null || !value.startsWith("=?base64?") || !value.endsWith("?=")) {
      return value;
    }
    String encoded = value.substring("=?base64?".length(), value.length() - 2);
    try {
      byte[] bytes = Base64.getDecoder().decode(encoded);
      return StandardCharsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString();
    } catch (IllegalArgumentException | CharacterCodingException error) {
      throw headerMismatch(id, headerName + " contains invalid Base64-encoded UTF-8");
    }
  }

  private static RpcException invalidRequest(Object id, String message) {
    return new RpcException(ERR_INVALID_REQUEST, 400, id, message, null);
  }

  private static RpcException invalidMetadata(Object id, String message) {
    return new RpcException(ERR_INVALID_PARAMS, 400, id, message, null);
  }

  private static RpcException headerMismatch(Object id, String message) {
    return new RpcException(ERR_HEADER_MISMATCH, 400, id,
        "Header mismatch: " + message, null);
  }

  private static RpcException invalidParams(String message) {
    return new RpcException(ERR_INVALID_PARAMS, 200, null, message, null);
  }

  private static String requireNonBlank(String value, String name) {
    if (isBlank(value)) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private record ParsedRequest(Object id, boolean hasId, String method, JsonObject params) {}

  private record ServerSettings(
      int port,
      String host,
      String basePath,
      Set<String> allowedOrigins,
      String authToken,
      int maxRequestsPerMinute,
      long maxBodyBytes,
      long toolTimeoutMs,
      int maxToolResultBytes) {

    static ServerSettings from(JsonObject config) {
      int port = config.getInteger("mcp.port", DEFAULT_MCP_PORT);
      String host = config.getString("mcp.host", DEFAULT_HOST);
      String basePath = normalizeBasePath(config.getString("mcp.basePath", ""));
      String authToken = config.getString("mcp.authToken", "");
      Set<String> allowedOrigins = parseOrigins(config.getValue("mcp.allowedOrigins"));
      int rateLimit = positive(config.getInteger("mcp.maxRequestsPerMinute", 120),
          "mcp.maxRequestsPerMinute");
      long maxBodyBytes = positive(number(config, "mcp.maxBodyBytes", 1_048_576L),
          "mcp.maxBodyBytes");
      long toolTimeoutMs = positive(number(config, "mcp.toolTimeoutMs", 30_000L),
          "mcp.toolTimeoutMs");
      int maxToolResultBytes = positive(config.getInteger("mcp.maxToolResultBytes", 1_048_576),
          "mcp.maxToolResultBytes");

      if (port < 0 || port > 65_535) {
        throw new IllegalArgumentException("mcp.port must be between 0 and 65535");
      }
      if (!isLoopback(host) && authToken.isBlank()) {
        throw new IllegalArgumentException(
            "mcp.authToken is required when mcp.host is not a loopback address");
      }
      return new ServerSettings(port, host, basePath, allowedOrigins, authToken,
          rateLimit, maxBodyBytes, toolTimeoutMs, maxToolResultBytes);
    }

    private static String normalizeBasePath(String value) {
      if (value == null || value.isBlank() || "/".equals(value)) {
        return "";
      }
      String path = value.startsWith("/") ? value : "/" + value;
      return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }

    private static Set<String> parseOrigins(Object value) {
      if (value == null) {
        return Set.of();
      }
      List<String> origins = new ArrayList<>();
      if (value instanceof JsonArray array) {
        array.forEach(item -> origins.add(String.valueOf(item).trim()));
      } else {
        for (String item : String.valueOf(value).split(",")) {
          origins.add(item.trim());
        }
      }
      origins.removeIf(String::isBlank);
      if (origins.contains("*")) {
        throw new IllegalArgumentException("mcp.allowedOrigins must list explicit origins; '*' is unsafe");
      }
      return Set.copyOf(origins);
    }

    private static long number(JsonObject config, String key, long fallback) {
      Number value = config.getNumber(key, fallback);
      return value.longValue();
    }

    private static boolean isLoopback(String host) {
      return "127.0.0.1".equals(host) || "::1".equals(host) || "localhost".equalsIgnoreCase(host);
    }

    private static int positive(int value, String key) {
      if (value <= 0) throw new IllegalArgumentException(key + " must be positive");
      return value;
    }

    private static long positive(long value, String key) {
      if (value <= 0) throw new IllegalArgumentException(key + " must be positive");
      return value;
    }
  }

  private static final class RateWindow {
    private long windowStarted = System.currentTimeMillis();
    private long lastSeen = windowStarted;
    private int count;

    synchronized boolean allow(int maximum) {
      long now = System.currentTimeMillis();
      lastSeen = now;
      if (now - windowStarted >= 60_000) {
        windowStarted = now;
        count = 0;
      }
      if (count >= maximum) {
        return false;
      }
      count++;
      return true;
    }

    synchronized long lastSeen() {
      return lastSeen;
    }
  }

  private static class RpcException extends RuntimeException {
    private final int code;
    private final int httpStatus;
    private final Object id;
    private final JsonObject data;

    RpcException(int code, int httpStatus, Object id, String message, JsonObject data) {
      super(message);
      this.code = code;
      this.httpStatus = httpStatus;
      this.id = id;
      this.data = data;
    }

    RpcException(int code, int httpStatus, Object id, String message,
                 JsonObject data, Throwable cause) {
      super(message, cause);
      this.code = code;
      this.httpStatus = httpStatus;
      this.id = id;
      this.data = data;
    }

    int code() { return code; }
    int httpStatus() { return httpStatus; }
    Object id() { return id; }
    JsonObject data() { return data; }
  }
}
