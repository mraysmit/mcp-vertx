package dev.mars.mcp;

import dev.mars.mcp.tool.CompleteToolResult;
import dev.mars.mcp.tool.ContentBlock;
import dev.mars.mcp.tool.InputRequiredToolResult;
import dev.mars.mcp.tool.Tool;
import dev.mars.mcp.tool.ToolContext;
import dev.mars.mcp.tool.ToolDefinition;
import dev.mars.mcp.tool.ToolExecutionException;
import dev.mars.mcp.tool.ToolInvocation;
import dev.mars.mcp.tool.ToolResult;
import dev.mars.mcp.tool.ToolSchemaValidator;
import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
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
import java.math.BigInteger;
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
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.logging.Level;
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
public final class McpServerVerticle extends VerticleBase {

  private static final Logger LOG = Logger.getLogger(McpServerVerticle.class.getName());
  private static final Pattern TOOL_NAME = Pattern.compile("[A-Za-z0-9_.-]{1,128}");
  private static final Pattern HTTP_FIELD_NAME = Pattern.compile(
      "[!#$%&'*+.^_`|~0-9A-Za-z-]+");

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
  private static final int ERR_MISSING_CAPABILITY = -32021;
  private static final int ERR_UNSUPPORTED_PROTOCOL = -32022;

  private static final int DEFAULT_MCP_PORT = 3001;
  private static final String DEFAULT_HOST = "127.0.0.1";

  private final Map<String, Tool> tools;
  private final Map<String, ToolDefinition> toolDefinitions;
  private final ToolSchemaValidator schemaValidator;
  private final String resourceIdField;
  private final Map<String, RateWindow> rateWindows = new ConcurrentHashMap<>();
  private final Map<String, AtomicInteger> activeByTool = new ConcurrentHashMap<>();
  private final AtomicInteger activeToolCalls = new AtomicInteger();
  private final AtomicInteger activeValidations = new AtomicInteger();
  private final LongAdder requests = new LongAdder();
  private final LongAdder toolCalls = new LongAdder();
  private final LongAdder toolErrors = new LongAdder();
  private final LongAdder toolTimeouts = new LongAdder();
  private final LongAdder rejectedCalls = new LongAdder();

  private volatile int actualPort = -1;
  private volatile ServerSettings settings;
  private long cleanupTimerId = -1;

  public McpServerVerticle(Map<String, Tool> tools, String resourceIdField) {
    TreeMap<String, Tool> sorted = new TreeMap<>(tools);
    TreeMap<String, ToolDefinition> definitions = new TreeMap<>();
    TreeMap<String, JsonObject> inputSchemas = new TreeMap<>();
    TreeMap<String, JsonObject> outputSchemas = new TreeMap<>();
    sorted.forEach((name, tool) -> {
      if (!TOOL_NAME.matcher(name).matches()) {
        throw new IllegalArgumentException(
            "Invalid MCP tool name '" + name + "'; expected 1-128 ASCII letters, digits, '.', '-', or '_'");
      }
      if (!name.equals(tool.name())) {
        throw new IllegalArgumentException("Tool registry key does not match tool name: " + name);
      }
      ToolDefinition definition = tool.definition();
      if (definition == null) {
        throw new IllegalArgumentException("Tool " + name + " returned a null definition");
      }
      if (!name.equals(definition.name())) {
        throw new IllegalArgumentException("Tool definition name does not match tool name: " + name);
      }
      JsonObject schema = definition.inputSchema();
      if (schema == null) {
        throw new IllegalArgumentException("Tool " + name + " returned a null input schema");
      }
      definitions.put(name, definition);
      inputSchemas.put(name, schema.copy());
      if (definition.outputSchema() != null) {
        outputSchemas.put(name, definition.outputSchema().copy());
      }
    });
    this.tools = Map.copyOf(sorted);
    this.toolDefinitions = Map.copyOf(definitions);
    this.schemaValidator = new ToolSchemaValidator(
        inputSchemas, outputSchemas, ToolSchemaValidator.SchemaLimits.defaults());
    this.resourceIdField = requireNonBlank(resourceIdField, "resourceIdField");
    LOG.info(() -> "MCP server initialized: tools=" + this.tools.size()
        + " resourceIdField=" + this.resourceIdField);
    LOG.fine(() -> "Initialized MCP tool definitions: " + this.tools.keySet());
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
  public Future<?> start() {
    settings = ServerSettings.from(config());

    String endpoint = settings.basePath() + "/mcp";
    LOG.info(() -> "Starting MCP HTTP server: host=" + settings.host()
        + " configuredPort=" + settings.port() + " endpoint=\"" + endpoint + "\""
        + " protocol=" + PROTOCOL_VERSION + " tools=" + tools.size());
    LOG.fine(() -> "MCP server limits: maxBodyBytes=" + settings.maxBodyBytes()
        + " maxResponseBytes=" + settings.maxResponseBytes()
        + " toolTimeoutMs=" + settings.toolTimeoutMs()
        + " validationTimeoutMs=" + settings.validationTimeoutMs()
        + " maxConcurrentToolCalls=" + settings.maxConcurrentToolCalls()
        + " maxConcurrentCallsPerTool=" + settings.maxConcurrentCallsPerTool()
        + " maxConcurrentValidations=" + settings.maxConcurrentValidations()
        + " maxRequestsPerMinute=" + settings.maxRequestsPerMinute()
        + " authConfigured=" + !settings.authToken().isBlank()
        + " allowedOrigins=" + settings.allowedOrigins().size()
        + " trustedProxies=" + settings.trustedProxies().size()
        + " healthEnabled=" + settings.healthEnabled());
    Router router = Router.router(vertx);
    router.route().handler(this::enforceTransportSecurity);
    router.options(endpoint).handler(this::handleOptions);
    router.post(endpoint).handler(this::validateMcpMediaHeaders);
    router.post(endpoint)
        .handler(BodyHandler.create(false)
            .setBodyLimit(settings.maxBodyBytes())
            .setMergeFormAttributes(false))
        .handler(this::handleMcpPost);
    router.get(endpoint).handler(this::methodNotAllowed);
    router.delete(endpoint).handler(this::methodNotAllowed);
    if (settings.healthEnabled()) {
      router.get(settings.basePath() + "/health/live").handler(this::handleLiveness);
      router.get(settings.basePath() + "/health/ready").handler(this::handleReadiness);
      LOG.fine(() -> "Health routes enabled under basePath=\""
          + settings.basePath() + "\"");
    }
    router.route().failureHandler(this::handleRoutingFailure);

    return vertx.createHttpServer()
        .requestHandler(router)
        .listen(settings.port(), settings.host())
        .map(server -> {
          actualPort = server.actualPort();
          cleanupTimerId = vertx.setPeriodic(120_000, ignored -> cleanupRateWindows());
          LOG.info("MCP server started on " + settings.host() + ":" + actualPort
              + " (endpoint=\"" + endpoint + "\", protocol=" + PROTOCOL_VERSION + ")");
          return null;
        });
  }

  @Override
  public Future<?> stop() {
    LOG.info(() -> "Stopping MCP server: port=" + actualPort
        + " requests=" + requests.sum() + " toolCalls=" + toolCalls.sum()
        + " toolErrors=" + toolErrors.sum() + " toolTimeouts=" + toolTimeouts.sum()
        + " rejectedCalls=" + rejectedCalls.sum());
    if (cleanupTimerId >= 0) {
      vertx.cancelTimer(cleanupTimerId);
      LOG.fine(() -> "Cancelled rate-window cleanup timer: timerId=" + cleanupTimerId);
    }
    rateWindows.clear();
    activeByTool.clear();
    actualPort = -1;
    LOG.info("MCP server stopped");
    return Future.succeededFuture();
  }

  private void enforceTransportSecurity(RoutingContext ctx) {
    HttpServerRequest request = ctx.request();
    String client = clientAddress(request);
    LOG.fine(() -> "Checking MCP transport security: method=" + request.method()
        + " path=\"" + request.path() + "\" client=" + client);
    String origin = request.getHeader("Origin");
    if (origin != null && !settings.allowedOrigins().contains(origin)) {
      LOG.info(() -> "Rejected MCP request from disallowed origin: client=" + client);
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
        LOG.info(() -> "Rejected unauthenticated MCP request: client=" + client);
        ctx.response().putHeader("WWW-Authenticate", "Bearer");
        sendRpcError(ctx.response(), 401, null, ERR_INVALID_REQUEST, "Authentication required", null);
        return;
      }
    }

    if (!"OPTIONS".equals(request.method().name())) {
      if (!rateWindows.computeIfAbsent(client, ignored -> new RateWindow())
          .allow(settings.maxRequestsPerMinute())) {
        LOG.info(() -> "Rate limited MCP client: client=" + client
            + " limitPerMinute=" + settings.maxRequestsPerMinute());
        ctx.response().putHeader("Retry-After", "60");
        sendRpcError(ctx.response(), 429, null, ERR_INTERNAL, "Request rate limit exceeded", null);
        return;
      }
    }
    LOG.fine(() -> "MCP transport security accepted request: client=" + client);
    ctx.next();
  }

  private void handleOptions(RoutingContext ctx) {
    LOG.fine(() -> "Handling MCP CORS preflight: client=" + clientAddress(ctx.request()));
    String requestedHeaders = ctx.request().getHeader("Access-Control-Request-Headers");
    String allowedHeaders = "Authorization, Content-Type, Accept, MCP-Protocol-Version, "
        + "Mcp-Method, Mcp-Name";
    if (requestedHeaders != null && !requestedHeaders.isBlank()) {
      for (String requested : requestedHeaders.split(",")) {
        String field = requested.trim();
        if (!HTTP_FIELD_NAME.matcher(field).matches()
            || (!field.regionMatches(true, 0, "Mcp-Param-", 0, 10)
                && !isStandardRequestHeader(field))) {
          sendRpcError(ctx.response(), 400, null, ERR_INVALID_REQUEST,
           "CORS requested an unsupported header", null);
          LOG.info(() -> "Rejected MCP CORS preflight with unsupported header: client="
              + clientAddress(ctx.request()));
          return;
        }
      }
      allowedHeaders = requestedHeaders;
    }
    ctx.response()
        .setStatusCode(204)
        .putHeader("Access-Control-Allow-Methods", "POST, OPTIONS")
        .putHeader("Access-Control-Allow-Headers", allowedHeaders)
        .putHeader("Access-Control-Max-Age", "600")
        .end()
        .onSuccess(ignored -> LOG.fine("MCP CORS preflight completed: status=204"))
        .onFailure(error -> LOG.log(Level.FINE, "Failed to write CORS response", error));
  }

  private static boolean isStandardRequestHeader(String value) {
    return Set.of("authorization", "content-type", "accept", "mcp-protocol-version",
            "mcp-method", "mcp-name")
        .contains(value.toLowerCase(Locale.ROOT));
  }

  private static boolean acceptsMediaType(String accept, String required) {
    if (accept == null) return false;
    for (String rawRange : accept.split(",")) {
      String[] parts = rawRange.trim().split(";");
      if (!required.equalsIgnoreCase(parts[0].trim())) continue;
      double quality = 1.0;
      for (int index = 1; index < parts.length; index++) {
        String parameter = parts[index].trim();
        int separator = parameter.indexOf('=');
        if (separator > 0 && "q".equalsIgnoreCase(parameter.substring(0, separator).trim())) {
          try {
            quality = Double.parseDouble(parameter.substring(separator + 1).trim());
          } catch (NumberFormatException error) {
            return false;
          }
        }
      }
      if (quality > 0.0 && quality <= 1.0) return true;
    }
    return false;
  }

  private void methodNotAllowed(RoutingContext ctx) {
    LOG.info(() -> "Rejected unsupported MCP HTTP method: method=" + ctx.request().method()
        + " client=" + clientAddress(ctx.request()));
    ctx.response().putHeader("Allow", "POST, OPTIONS");
    sendRpcError(ctx.response(), 405, null, ERR_INVALID_REQUEST,
        "The current MCP Streamable HTTP endpoint only accepts POST", null);
  }

  private void validateMcpMediaHeaders(RoutingContext ctx) {
    requests.increment();
    HttpServerRequest request = ctx.request();
    LOG.fine(() -> "Validating MCP media headers: client=" + clientAddress(request));
    String contentType = request.getHeader("Content-Type");
    String mediaType = contentType == null
        ? "" : contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    if (!"application/json".equals(mediaType)) {
      LOG.info(() -> "Rejected MCP request with unsupported Content-Type: mediaType=\""
          + mediaType + "\" client=" + clientAddress(request));
      sendRpcError(ctx.response(), 415, null, ERR_INVALID_REQUEST,
          "Content-Type must be application/json", null);
      return;
    }

    String accept = request.getHeader("Accept");
    if (!acceptsMediaType(accept, "application/json")
        || !acceptsMediaType(accept, "text/event-stream")) {
      LOG.info(() -> "Rejected MCP request with unacceptable response types: client="
          + clientAddress(request));
      sendRpcError(ctx.response(), 406, null, ERR_INVALID_REQUEST,
          "Accept must list application/json and text/event-stream", null);
      return;
    }
    LOG.fine("MCP media headers accepted");
    ctx.next();
  }

  private void handleMcpPost(RoutingContext ctx) {
    HttpServerRequest request = ctx.request();
    HttpServerResponse response = ctx.response();
    long startedNanos = System.nanoTime();

    String body = ctx.body().asString();
    LOG.fine(() -> "Decoding MCP request body: bytes="
        + (body == null ? 0 : body.getBytes(StandardCharsets.UTF_8).length)
        + " client=" + clientAddress(request));
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
    LOG.info(() -> "Accepted MCP request: method=" + safeLogValue(parsed.method())
        + " notification=" + !parsed.hasId() + " client=" + clientAddress(request));
    LOG.fine(() -> "MCP request metadata validated: method=" + safeLogValue(parsed.method())
        + " hasParams=" + !parsed.params().isEmpty());

    if (!parsed.hasId()) {
      if (!parsed.method().startsWith("notifications/")) {
        sendRpcError(response, 400, null, ERR_INVALID_REQUEST,
            "Method " + parsed.method() + " requires a JSON-RPC id", null);
        return;
      }
      LOG.info(() -> "Accepted MCP extension notification: method="
          + safeLogValue(parsed.method()));
      response.setStatusCode(202).end()
          .onSuccess(ignored -> LOG.fine(() -> "MCP notification completed: method="
              + safeLogValue(parsed.method())
              + " durationMs=" + elapsedMillis(startedNanos)))
          .onFailure(error -> LOG.log(Level.FINE,
              "Failed to write notification response", error));
      return;
    }

    Future<JsonObject> execution;
    try {
      execution = dispatch(parsed, request);
    } catch (RuntimeException error) {
      execution = Future.failedFuture(error);
    }
    execution
        .onSuccess(result -> {
          LOG.info(() -> "MCP request completed: method=" + safeLogValue(parsed.method())
              + " durationMs=" + elapsedMillis(startedNanos));
          sendJsonResponse(response, parsed.id(), result);
        })
        .onFailure(error -> {
          RpcException rpcError = error instanceof RpcException re
              ? re
              : new RpcException(ERR_INTERNAL, 500, parsed.id(),
                  "Internal server error", null, error);
          String failureMessage = "MCP request failed: method=" + safeLogValue(parsed.method())
              + " status=" + rpcError.httpStatus() + " code=" + rpcError.code()
              + " durationMs=" + elapsedMillis(startedNanos)
              + " error=" + safeLogValue(rpcError.getMessage());
          if (rpcError.getCause() == null) {
            LOG.warning(failureMessage);
          } else {
            LOG.log(Level.WARNING, failureMessage, rpcError.getCause());
          }
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
    if (headerVersion == null || headerVersion.isBlank()) {
      throw headerMismatch(parsed.id(), "MCP-Protocol-Version header is required");
    }
    JsonObject meta = asJsonObject(parsed.params().getValue("_meta"));
    if (meta == null) {
      throw invalidMetadata(parsed.id(), "params._meta must be an object");
    }
    Object rawBodyVersion = meta.getValue(META_PROTOCOL_VERSION);
    if (!(rawBodyVersion instanceof String bodyVersion) || bodyVersion.isBlank()) {
      throw invalidMetadata(parsed.id(),
          "params._meta must contain io.modelcontextprotocol/protocolVersion as a string");
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
    JsonObject clientCapabilities = asJsonObject(meta.getValue(META_CLIENT_CAPABILITIES));
    if (clientCapabilities == null) {
      throw invalidMetadata(parsed.id(),
          "params._meta must contain io.modelcontextprotocol/clientCapabilities");
    }
    validateClientCapabilities(clientCapabilities, parsed.id());
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

  private void validateClientCapabilities(JsonObject capabilities, Object requestId) {
    for (String name : List.of("roots", "sampling", "elicitation")) {
      if (capabilities.containsKey(name)
          && asJsonObject(capabilities.getValue(name)) == null) {
        throw invalidMetadata(requestId, "client capability '" + name + "' must be an object");
      }
    }
    JsonObject sampling = asJsonObject(capabilities.getValue("sampling"));
    if (sampling != null) {
      for (String name : List.of("context", "tools")) {
        if (sampling.containsKey(name) && asJsonObject(sampling.getValue(name)) == null) {
          throw invalidMetadata(requestId,
              "client capability 'sampling." + name + "' must be an object");
        }
      }
    }
    JsonObject elicitation = asJsonObject(capabilities.getValue("elicitation"));
    if (elicitation != null) {
      for (String name : List.of("form", "url")) {
        if (elicitation.containsKey(name)
            && asJsonObject(elicitation.getValue(name)) == null) {
          throw invalidMetadata(requestId,
              "client capability 'elicitation." + name + "' must be an object");
        }
      }
    }
  }

  private Future<JsonObject> dispatch(ParsedRequest request, HttpServerRequest httpRequest) {
    LOG.fine(() -> "Dispatching MCP request: method=" + safeLogValue(request.method()));
    return switch (request.method()) {
      case "server/discover" -> Future.succeededFuture(discoverResult());
      case "tools/list" -> Future.succeededFuture(toolsListResult());
      case "tools/call" -> callTool(request.params(), httpRequest, request.id());
      default -> Future.failedFuture(new RpcException(
          ERR_METHOD_NOT_FOUND, 404, request.id(),
          "Method not found: " + request.method(), null));
    };
  }

  private JsonObject discoverResult() {
    LOG.fine(() -> "Rendering server discovery: protocol=" + PROTOCOL_VERSION
        + " tools=" + tools.size());
    return completeResult()
        .put("supportedVersions", new JsonArray().add(PROTOCOL_VERSION))
        .put("capabilities", serverCapabilities())
        .put("instructions", "Call tools/list to discover available tools before invoking tools/call.")
        .put("ttlMs", 3_600_000)
        .put("cacheScope", "public");
  }

  private JsonObject toolsListResult() {
    LOG.info(() -> "Listing MCP tools: count=" + toolDefinitions.size());
    JsonArray toolList = new JsonArray();
    toolDefinitions.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(entry -> toolList.add(entry.getValue().toJson()));
    return completeResult()
        .put("tools", toolList)
        .put("ttlMs", 60_000)
        .put("cacheScope", "public");
  }

  private Future<JsonObject> callTool(JsonObject params, HttpServerRequest request, Object requestId) {
    Object rawToolName = params.getValue("name");
    String toolName = rawToolName instanceof String value ? value : null;
    if (isBlank(toolName)) {
      LOG.info("Rejected tools/call without a tool name");
      return Future.failedFuture(invalidParams("Missing required parameter: name"));
    }
    Tool tool = tools.get(toolName);
    if (tool == null) {
      LOG.info(() -> "Rejected tools/call for unknown tool: tool=" + safeLogValue(toolName));
      return Future.failedFuture(invalidParams("Unknown tool: " + toolName));
    }

    JsonObject arguments = new JsonObject();
    if (params.containsKey("arguments")) {
      arguments = asJsonObject(params.getValue("arguments"));
      if (arguments == null) {
        LOG.info(() -> "Rejected tools/call with non-object arguments: tool=" + toolName);
        return Future.failedFuture(invalidParams("arguments must be an object"));
      }
    }
    try {
      validateInputResponseParams(params, requestId);
    } catch (RpcException error) {
      return Future.failedFuture(error);
    }
    try {
      validateToolHeaders(toolName, arguments, request, requestId);
    } catch (RpcException error) {
      return Future.failedFuture(error);
    }

    JsonObject invocationArguments = arguments.copy();
    LOG.fine(() -> "Preparing MCP tool invocation: tool=" + toolName
        + " argumentFields=" + invocationArguments.fieldNames().size()
        + " mirroredHeaders=" + schemaValidator.headerBindings(toolName).size());
    return validateSchema(toolName, invocationArguments, false)
        .compose(validationError -> {
          if (!validationError.isEmpty()) {
            LOG.info(() -> "Rejected invalid MCP tool arguments: tool=" + toolName);
            return Future.succeededFuture(failedToolResult(
                new ToolExecutionException("invalid_arguments",
                    "Invalid tool arguments: " + validationError, false, null)));
          }
          return invokeTool(toolName, tool, invocationArguments, params, request, requestId);
        })
        .recover(error -> error instanceof ToolExecutionException
            ? Future.succeededFuture(failedToolResult(error))
            : Future.failedFuture(error));
  }

  private void validateInputResponseParams(JsonObject params, Object requestId) {
    if (params.containsKey("requestState")
        && !(params.getValue("requestState") instanceof String)) {
      throw new RpcException(ERR_INVALID_PARAMS, 200, requestId,
          "requestState must be a string", null);
    }
    if (!params.containsKey("inputResponses")) return;
    JsonObject responses = asJsonObject(params.getValue("inputResponses"));
    if (responses == null) {
      throw new RpcException(ERR_INVALID_PARAMS, 200, requestId,
          "inputResponses must be an object", null);
    }
    responses.forEach(entry -> {
      if (entry.getKey() == null || entry.getKey().isBlank()
          || !(entry.getValue() instanceof JsonObject response)
          || !(response.getValue("resultType") instanceof String resultType)
          || resultType.isBlank()) {
        throw new RpcException(ERR_INVALID_PARAMS, 200, requestId,
            "Each inputResponses entry must have a request ID and resultType", null);
      }
    });
  }

  private Future<JsonObject> invokeTool(String toolName, Tool tool, JsonObject arguments,
                                        JsonObject params, HttpServerRequest request,
                                        Object requestId) {
    if (!tryAcquire(toolName)) {
      rejectedCalls.increment();
      LOG.info(() -> "Rejected MCP tool invocation at concurrency limit: tool=" + toolName
          + " activeGlobal=" + activeToolCalls.get());
      return Future.succeededFuture(failedToolResult(new ToolExecutionException(
          "server_busy", "Tool concurrency limit exceeded; retry later", true, null)));
    }

    String correlationId = UUID.randomUUID().toString();
    Object rawResourceId = arguments.getValue(resourceIdField);
    String resourceId = rawResourceId instanceof String value && !value.isBlank()
        ? value : "mcp-" + correlationId;
    JsonObject requestMeta = asJsonObject(params.getValue("_meta"));
    JsonObject metadata = new JsonObject()
        .put("protocolVersion", PROTOCOL_VERSION)
        .put("remoteAddress", clientAddress(request))
        .put("client", requestMeta == null ? null
            : asJsonObject(requestMeta.getValue(META_CLIENT_INFO)))
        .put("inputResponses", params.getValue("inputResponses"))
        .put("requestState", params.getValue("requestState"));
    long now = System.currentTimeMillis();
    long deadline = settings.toolTimeoutMs() > Long.MAX_VALUE - now
        ? Long.MAX_VALUE : now + settings.toolTimeoutMs();
    ToolContext context = new ToolContext(correlationId, resourceId, metadata, deadline);
    long startedNanos = System.nanoTime();
    LOG.fine(() -> "Created MCP tool context: tool=" + toolName
        + " correlationId=" + correlationId + " deadlineEpochMs=" + deadline
        + " activeGlobal=" + activeToolCalls.get());

    ToolInvocation invocation;
    try {
      invocation = tool.invokeManaged(arguments.copy(), context);
      if (invocation == null) {
        LOG.warning("MCP tool returned a null invocation: tool=" + toolName
            + " correlationId=" + correlationId);
        invocation = ToolInvocation.of(Future.failedFuture("Tool returned a null invocation"));
      }
    } catch (RuntimeException error) {
      LOG.log(Level.WARNING, "MCP tool threw before returning an invocation: tool="
          + toolName + " correlationId=" + correlationId, error);
      invocation = ToolInvocation.of(Future.failedFuture(error));
    }

    toolCalls.increment();
    LOG.info("MCP tools/call: tool=" + toolName + " correlationId=" + correlationId);
    ToolInvocation managed = invocation;
    Future<ToolResult> providerResult = managed.result();
    providerResult.onComplete(completion -> {
      release(toolName);
      LOG.fine(() -> "MCP provider future completed: tool=" + toolName
          + " correlationId=" + correlationId
          + " succeeded=" + completion.succeeded()
          + " durationMs=" + elapsedMillis(startedNanos)
          + " activeGlobal=" + activeToolCalls.get());
    });
    Future<ToolResult> outcome = providerResult
        .timeout(settings.toolTimeoutMs(), TimeUnit.MILLISECONDS)
        .compose(result -> validateToolOutput(toolName, result))
        .recover(error -> handleToolFailure(error, managed, context, toolName, correlationId));

    return outcome
        .onSuccess(result -> LOG.info(() -> "MCP tool invocation completed: tool=" + toolName
            + " correlationId=" + correlationId
            + " resultType=" + result.getClass().getSimpleName()
            + " durationMs=" + elapsedMillis(startedNanos)))
        .compose(result -> renderToolResult(result, requestMeta, requestId));
  }

  private Future<ToolResult> validateToolOutput(String toolName, ToolResult result) {
    if (result == null) {
      return Future.failedFuture("Tool returned no result");
    }
    if (result instanceof CompleteToolResult complete && complete.hasStructuredContent()) {
      LOG.fine(() -> "Validating structured MCP tool output: tool=" + toolName);
      return validateSchema(toolName, complete.structuredContentValue(), true)
          .compose(error -> error.isEmpty()
              ? Future.succeededFuture(result)
              : Future.failedFuture("Tool output did not match its advertised schema: " + error));
    }
    LOG.fine(() -> "No structured MCP output validation required: tool=" + toolName
        + " resultType=" + result.getClass().getSimpleName());
    return Future.succeededFuture(result);
  }

  private Future<ToolResult> handleToolFailure(Throwable error, ToolInvocation invocation,
                                               ToolContext context, String toolName,
                                               String correlationId) {
    if (error instanceof TimeoutException) {
      toolTimeouts.increment();
      toolErrors.increment();
      context.cancel();
      LOG.warning("MCP tool timed out: tool=" + toolName + " correlationId=" + correlationId);
      return invocation.cancel()
          .timeout(settings.cancellationGraceMs(), TimeUnit.MILLISECONDS)
          .onSuccess(ignored -> LOG.fine(() -> "Tool cancellation completed: tool="
              + toolName + " correlationId=" + correlationId))
          .onFailure(cancelError -> LOG.log(Level.WARNING,
              "Tool cancellation failed: tool=" + toolName
                  + " correlationId=" + correlationId, cancelError))
          .recover(ignored -> Future.succeededFuture())
          .map(ignored -> failedToolResultValue(new ToolExecutionException(
              "timeout", "Tool execution timed out", true, error)));
    }
    toolErrors.increment();
    if (error instanceof ToolExecutionException safe) {
      LOG.info(() -> "MCP tool reported a safe failure: tool=" + toolName
          + " correlationId=" + correlationId + " errorType=" + safe.errorType()
          + " retryable=" + safe.retryable());
    } else {
      LOG.log(Level.WARNING, "MCP tool failed: tool=" + toolName
          + " correlationId=" + correlationId, error);
    }
    return Future.succeededFuture(failedToolResultValue(error));
  }

  private Future<JsonObject> renderToolResult(ToolResult result, JsonObject requestMeta,
                                              Object requestId) {
    LOG.fine(() -> "Rendering MCP tool result: resultType="
        + result.getClass().getSimpleName());
    if (result instanceof InputRequiredToolResult inputRequired) {
      validateInputCapabilities(inputRequired, requestMeta, requestId);
    }
    JsonObject json = result.toJson();
    JsonObject meta = asJsonObject(json.getValue("_meta"));
    if (meta == null) meta = new JsonObject();
    meta.put(META_SERVER_INFO, serverInfo());
    json.put("_meta", meta);
    return Future.succeededFuture(json);
  }

  private JsonObject failedToolResult(Throwable error) {
    return withServerInfo(failedToolResultValue(error).toJson());
  }

  private CompleteToolResult failedToolResultValue(Throwable error) {
    String message = error instanceof ToolExecutionException safe
        ? safe.getMessage() : "Tool execution failed";
    if (message.length() > 500) {
      message = message.substring(0, 500);
    }
    JsonObject metadata = new JsonObject();
    if (error instanceof ToolExecutionException safe) {
      metadata.put("errorType", safe.errorType()).put("retryable", safe.retryable());
    }
    return new CompleteToolResult(List.of(ContentBlock.text(message)), null, true, metadata);
  }

  private JsonObject withServerInfo(JsonObject result) {
    JsonObject meta = asJsonObject(result.getValue("_meta"));
    if (meta == null) meta = new JsonObject();
    result.put("_meta", meta.put(META_SERVER_INFO, serverInfo()));
    return result;
  }

  private Future<String> validateSchema(String toolName, Object value, boolean output) {
    long startedNanos = System.nanoTime();
    int active = activeValidations.incrementAndGet();
    LOG.fine(() -> "Scheduling MCP schema validation: tool=" + toolName
        + " kind=" + (output ? "output" : "input") + " active=" + active);
    if (active > settings.maxConcurrentValidations()) {
      activeValidations.decrementAndGet();
      rejectedCalls.increment();
      LOG.info(() -> "Rejected MCP schema validation at concurrency limit: tool=" + toolName
          + " active=" + active + " limit=" + settings.maxConcurrentValidations());
      return Future.failedFuture(new ToolExecutionException(
          "server_busy", "Schema validation capacity exceeded; retry later", true, null));
    }
    Future<String> validation = vertx.executeBlocking(() -> output
        ? schemaValidator.validateOutput(toolName, value)
        : schemaValidator.validate(toolName, (JsonObject) value), false);
    validation.onComplete(completion -> {
      int remaining = activeValidations.decrementAndGet();
      LOG.fine(() -> "MCP schema validation completed: tool=" + toolName
          + " kind=" + (output ? "output" : "input")
          + " succeeded=" + completion.succeeded()
          + " durationMs=" + elapsedMillis(startedNanos)
          + " active=" + remaining);
    });
    return validation.timeout(settings.validationTimeoutMs(), TimeUnit.MILLISECONDS)
        .recover(error -> error instanceof TimeoutException
            ? Future.failedFuture(new ToolExecutionException(
                "validation_timeout", "Schema validation timed out; retry later", true, error))
            : Future.failedFuture(error));
  }

  private boolean tryAcquire(String toolName) {
    int global = activeToolCalls.incrementAndGet();
    if (global > settings.maxConcurrentToolCalls()) {
      activeToolCalls.decrementAndGet();
      LOG.fine(() -> "Global MCP tool concurrency limit reached: tool=" + toolName
          + " active=" + global + " limit=" + settings.maxConcurrentToolCalls());
      return false;
    }
    AtomicInteger toolCounter = activeByTool.computeIfAbsent(toolName,
        ignored -> new AtomicInteger());
    int perTool = toolCounter.incrementAndGet();
    if (perTool > settings.maxConcurrentCallsPerTool()) {
      toolCounter.decrementAndGet();
      activeToolCalls.decrementAndGet();
      LOG.fine(() -> "Per-tool MCP concurrency limit reached: tool=" + toolName
          + " active=" + perTool + " limit=" + settings.maxConcurrentCallsPerTool());
      return false;
    }
    LOG.fine(() -> "Acquired MCP tool concurrency slot: tool=" + toolName
        + " activeGlobal=" + global + " activeTool=" + perTool);
    return true;
  }

  private void release(String toolName) {
    int global = activeToolCalls.decrementAndGet();
    AtomicInteger counter = activeByTool.get(toolName);
    int perTool = counter == null ? 0 : counter.decrementAndGet();
    LOG.fine(() -> "Released MCP tool concurrency slot: tool=" + toolName
        + " activeGlobal=" + global + " activeTool=" + perTool);
  }

  private void validateInputCapabilities(InputRequiredToolResult result,
                                         JsonObject requestMeta, Object requestId) {
    JsonObject capabilities = requestMeta == null ? null
        : asJsonObject(requestMeta.getValue(META_CLIENT_CAPABILITIES));
    JsonObject required = requiredCapabilities(result.inputRequests());
    JsonObject missing = missingCapabilities(required, capabilities);
    if (!missing.isEmpty()) {
      LOG.info(() -> "Rejected input-required tool result; client capabilities missing: count="
          + missing.size());
      throw new RpcException(ERR_MISSING_CAPABILITY, 400, requestId,
          "Client does not advertise capabilities required by the tool result",
          new JsonObject().put("requiredCapabilities", missing));
    }
    LOG.fine(() -> "Validated input-required client capabilities: required="
        + required.size());
  }

  private JsonObject requiredCapabilities(JsonObject inputRequests) {
    JsonObject required = new JsonObject();
    if (inputRequests == null) return required;
    inputRequests.forEach(entry -> {
      JsonObject request = (JsonObject) entry.getValue();
      String method = request.getString("method");
      JsonObject params = request.getJsonObject("params", new JsonObject());
      switch (method) {
        case "sampling/createMessage" -> {
          JsonObject sampling = required.getJsonObject("sampling", new JsonObject());
          if (params.containsKey("tools") || params.containsKey("toolChoice")) {
            sampling.put("tools", new JsonObject());
          }
          Object includeContext = params.getValue("includeContext");
          if (includeContext instanceof String value && !"none".equals(value)) {
            sampling.put("context", new JsonObject());
          }
          required.put("sampling", sampling);
        }
        case "elicitation/create" -> {
          JsonObject elicitation = required.getJsonObject("elicitation", new JsonObject());
          if ("url".equals(params.getString("mode"))) {
            elicitation.put("url", new JsonObject());
          }
          required.put("elicitation", elicitation);
        }
        case "roots/list" -> required.put("roots", new JsonObject());
        default -> throw new IllegalArgumentException("Unsupported MCP input request method: " + method);
      }
    });
    return required;
  }

  private JsonObject missingCapabilities(JsonObject required, JsonObject supplied) {
    JsonObject missing = new JsonObject();
    required.forEach(entry -> {
      JsonObject requiredValue = (JsonObject) entry.getValue();
      JsonObject suppliedValue = supplied == null ? null
          : asJsonObject(supplied.getValue(entry.getKey()));
      if (suppliedValue == null) {
        missing.put(entry.getKey(), requiredValue.copy());
        return;
      }
      JsonObject missingNested = new JsonObject();
      requiredValue.forEach(nested -> {
        if (asJsonObject(suppliedValue.getValue(nested.getKey())) == null) {
          missingNested.put(nested.getKey(), new JsonObject());
        }
      });
      if (!missingNested.isEmpty()) missing.put(entry.getKey(), missingNested);
    });
    return missing;
  }

  private void validateToolHeaders(String toolName, JsonObject arguments,
                                   HttpServerRequest request, Object requestId) {
    List<ToolSchemaValidator.HeaderBinding> bindings = schemaValidator.headerBindings(toolName);
    LOG.fine(() -> "Validating mirrored MCP parameter headers: tool=" + toolName
        + " bindings=" + bindings.size());
    for (ToolSchemaValidator.HeaderBinding binding : bindings) {
      LocatedValue body = locate(arguments, binding.path());
      String fieldName = "Mcp-Param-" + binding.name();
      String rawHeader = request.getHeader(fieldName);
      if (!body.present() || body.value() == null) {
        if (rawHeader != null) {
          throw headerMismatch(requestId, fieldName
              + " must be absent when its mirrored argument is absent or null");
        }
        continue;
      }
      if (rawHeader == null) {
        throw headerMismatch(requestId, fieldName + " is required");
      }
      String header = decodeMirroredHeader(rawHeader, requestId, fieldName);
      boolean matches = switch (binding.type()) {
        case STRING -> body.value() instanceof String value && value.equals(header);
        case BOOLEAN -> body.value() instanceof Boolean value
            && value.toString().equals(header);
        case INTEGER -> integerHeaderMatches(body.value(), header);
      };
      if (!matches) {
        throw headerMismatch(requestId, fieldName + " must match its JSON argument");
      }
    }
    LOG.fine(() -> "Mirrored MCP parameter headers validated: tool=" + toolName
        + " bindings=" + bindings.size());
  }

  private static boolean integerHeaderMatches(Object body, String header) {
    if (!(body instanceof Number number)) return false;
    try {
      BigInteger bodyValue = new java.math.BigDecimal(number.toString()).toBigIntegerExact();
      BigInteger headerValue = new BigInteger(header);
      BigInteger maximum = BigInteger.valueOf(9_007_199_254_740_991L);
      return bodyValue.abs().compareTo(maximum) <= 0 && bodyValue.equals(headerValue);
    } catch (NumberFormatException | ArithmeticException error) {
      return false;
    }
  }

  private static LocatedValue locate(JsonObject root, List<String> path) {
    Object current = root;
    for (String segment : path) {
      if (current instanceof JsonObject object) {
        if (!object.containsKey(segment)) return new LocatedValue(false, null);
        current = object.getValue(segment);
      } else if (current instanceof Map<?, ?> map) {
        if (!map.containsKey(segment)) return new LocatedValue(false, null);
        current = map.get(segment);
      } else {
        return new LocatedValue(false, null);
      }
    }
    return new LocatedValue(true, current);
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
    JsonObject envelope = new JsonObject()
        .put("jsonrpc", "2.0")
        .put("id", id)
        .put("result", result);
    if (utf8Size(envelope) > settings.maxResponseBytes()) {
      LOG.warning("MCP response exceeded configured size limit");
      sendRpcError(response, 500, id, ERR_INTERNAL,
          "Response exceeded the configured size limit", null);
      return;
    }
    endJson(response, 200, envelope);
  }

  private void sendRpcError(HttpServerResponse response, int status, Object id,
                            int code, String message, JsonObject data) {
    int requestedStatus = status;
    LOG.fine(() -> "Rendering JSON-RPC error: httpStatus=" + requestedStatus
        + " code=" + code + " message=\"" + safeLogValue(message) + "\"");
    JsonObject error = new JsonObject().put("code", code).put("message", message);
    if (data != null) {
      error.put("data", data);
    }
    JsonObject envelope = new JsonObject()
        .put("jsonrpc", "2.0")
        .put("id", id)
        .put("error", error);
    if (settings != null && utf8Size(envelope) > settings.maxResponseBytes()) {
      LOG.warning("JSON-RPC error exceeded configured size limit; using bounded fallback");
      envelope = new JsonObject().put("jsonrpc", "2.0").put("id", id)
          .put("error", new JsonObject().put("code", ERR_INTERNAL)
              .put("message", "Internal server error"));
      status = 500;
    }
    endJson(response, status, envelope);
  }

  private void endJson(HttpServerResponse response, int status, JsonObject envelope) {
    if (response.ended()) {
      LOG.fine(() -> "Skipped duplicate HTTP response: status=" + status);
      return;
    }
    int bytes = utf8Size(envelope);
    LOG.fine(() -> "Writing MCP HTTP response: status=" + status + " bytes=" + bytes);
    response.setStatusCode(status)
        .putHeader("Content-Type", "application/json; charset=utf-8")
        .end(envelope.encode())
        .onSuccess(ignored -> LOG.fine(() -> "MCP HTTP response written: status=" + status
            + " bytes=" + bytes))
        .onFailure(error -> LOG.log(Level.FINE, "Failed to write HTTP response", error));
  }

  private static int utf8Size(JsonObject value) {
    return value.encode().getBytes(StandardCharsets.UTF_8).length;
  }

  private void handleLiveness(RoutingContext ctx) {
    LOG.fine(() -> "Serving MCP liveness probe: client=" + clientAddress(ctx.request()));
    endJson(ctx.response(), 200, new JsonObject().put("status", "UP"));
  }

  private void handleReadiness(RoutingContext ctx) {
    boolean ready = actualPort >= 0
        && activeValidations.get() < settings.maxConcurrentValidations();
    JsonObject health = new JsonObject()
        .put("status", ready ? "UP" : "DOWN")
        .put("activeToolCalls", activeToolCalls.get())
        .put("activeValidations", activeValidations.get())
        .put("requests", requests.sum())
        .put("toolCalls", toolCalls.sum())
        .put("toolErrors", toolErrors.sum())
        .put("toolTimeouts", toolTimeouts.sum())
        .put("rejectedCalls", rejectedCalls.sum());
    LOG.fine(() -> "Serving MCP readiness probe: ready=" + ready
        + " activeToolCalls=" + activeToolCalls.get()
        + " activeValidations=" + activeValidations.get());
    endJson(ctx.response(), ready ? 200 : 503, health);
  }

  private void handleRoutingFailure(RoutingContext ctx) {
    if (ctx.response().ended()) {
      return;
    }
    int status = ctx.statusCode() >= 400 ? ctx.statusCode() : 500;
    String message = status == 413 ? "Request body exceeds the configured limit" : "HTTP request failed";
    Throwable failure = ctx.failure();
    String logMessage = "MCP routing failure: status=" + status
        + " method=" + ctx.request().method() + " path=\""
        + safeLogValue(ctx.request().path())
        + "\" client=" + clientAddress(ctx.request());
    if (failure == null) {
      LOG.warning(logMessage);
    } else {
      LOG.log(Level.WARNING, logMessage, failure);
    }
    sendRpcError(ctx.response(), status, null,
        status == 413 ? ERR_INVALID_REQUEST : ERR_INTERNAL, message, null);
  }

  private String clientAddress(HttpServerRequest request) {
    String direct = request.remoteAddress() == null
        ? "unknown" : request.remoteAddress().hostAddress();
    if (!settings.trustedProxies().contains(direct)) return direct;
    String forwarded = request.getHeader(settings.clientAddressHeader());
    if (forwarded == null) return direct;
    String candidate = forwarded.split(",", 2)[0].trim();
    boolean valid = candidate.length() <= 45 && candidate.matches("[0-9A-Fa-f:.]+");
    LOG.fine(() -> "Evaluated forwarded MCP client address: proxy=" + direct
        + " accepted=" + valid);
    return valid ? candidate : direct;
  }

  private void cleanupRateWindows() {
    long cutoff = System.currentTimeMillis() - 120_000;
    int before = rateWindows.size();
    rateWindows.entrySet().removeIf(entry -> entry.getValue().lastSeen() < cutoff);
    int removed = before - rateWindows.size();
    LOG.fine(() -> "Cleaned MCP rate windows: removed=" + removed
        + " remaining=" + rateWindows.size());
  }

  private static long elapsedMillis(long startedNanos) {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
  }

  private static String safeLogValue(Object value) {
    String text = String.valueOf(value);
    StringBuilder safe = new StringBuilder(Math.min(text.length(), 160));
    for (int index = 0; index < text.length() && safe.length() < 160; index++) {
      char character = text.charAt(index);
      safe.append(Character.isISOControl(character) ? '?' : character);
    }
    return safe.toString();
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
    if (value == null) return null;
    if (!value.startsWith("=?base64?") || !value.endsWith("?=")) {
      if (!isPlainHeaderValue(value)) {
        throw headerMismatch(id, headerName
            + " must be visible ASCII without leading or trailing whitespace, or Base64 encoded");
      }
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

  private static boolean isPlainHeaderValue(String value) {
    if (!value.isEmpty()
        && (value.charAt(0) == ' ' || value.charAt(0) == '\t'
            || value.charAt(value.length() - 1) == ' '
            || value.charAt(value.length() - 1) == '\t')) {
      return false;
    }
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      if (character < 0x20 || character > 0x7e) return false;
    }
    return true;
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

  private record LocatedValue(boolean present, Object value) {}

  private record ServerSettings(
      int port,
      String host,
      String basePath,
      Set<String> allowedOrigins,
      String authToken,
      int maxRequestsPerMinute,
      long maxBodyBytes,
      long toolTimeoutMs,
      long validationTimeoutMs,
      long cancellationGraceMs,
      int maxConcurrentToolCalls,
      int maxConcurrentCallsPerTool,
      int maxConcurrentValidations,
      long maxResponseBytes,
      boolean healthEnabled,
      Set<String> trustedProxies,
      String clientAddressHeader) {

    static ServerSettings from(JsonObject config) {
      int port = config.getInteger("mcp.port", DEFAULT_MCP_PORT);
      String host = requireNonBlank(config.getString("mcp.host", DEFAULT_HOST), "mcp.host");
      String basePath = normalizeBasePath(config.getString("mcp.basePath", ""));
      String authToken = config.getString("mcp.authToken", "");
      Set<String> allowedOrigins = parseOrigins(config.getValue("mcp.allowedOrigins"));
      int rateLimit = positive(config.getInteger("mcp.maxRequestsPerMinute", 120),
          "mcp.maxRequestsPerMinute");
      long maxBodyBytes = positive(number(config, "mcp.maxBodyBytes", 1_048_576L),
          "mcp.maxBodyBytes");
      long toolTimeoutMs = positive(number(config, "mcp.toolTimeoutMs", 30_000L),
          "mcp.toolTimeoutMs");
      long validationTimeoutMs = positive(number(config, "mcp.validationTimeoutMs", 2_000L),
          "mcp.validationTimeoutMs");
      long cancellationGraceMs = positive(number(config, "mcp.cancellationGraceMs", 250L),
          "mcp.cancellationGraceMs");
      int maxConcurrentToolCalls = positive(
          config.getInteger("mcp.maxConcurrentToolCalls", 64), "mcp.maxConcurrentToolCalls");
      int maxConcurrentCallsPerTool = positive(
          config.getInteger("mcp.maxConcurrentCallsPerTool", 16),
          "mcp.maxConcurrentCallsPerTool");
      int maxConcurrentValidations = positive(
          config.getInteger("mcp.maxConcurrentValidations", 32),
          "mcp.maxConcurrentValidations");
      long legacyResponseLimit = number(config, "mcp.maxToolResultBytes", 1_048_576L);
      long maxResponseBytes = positive(number(config, "mcp.maxResponseBytes", legacyResponseLimit),
          "mcp.maxResponseBytes");
      boolean healthEnabled = booleanValue(config, "mcp.healthEnabled", false);
      Set<String> trustedProxies = parseCsvSet(config.getValue("mcp.trustedProxies"));
      String clientAddressHeader = config.getString("mcp.clientAddressHeader", "X-Forwarded-For");

      if (port < 0 || port > 65_535) {
        throw new IllegalArgumentException("mcp.port must be between 0 and 65535");
      }
      if (!isLoopback(host) && authToken.isBlank()) {
        throw new IllegalArgumentException(
            "mcp.authToken is required when mcp.host is not a loopback address");
      }
      if (maxResponseBytes < 512) {
        throw new IllegalArgumentException("mcp.maxResponseBytes must be at least 512");
      }
      if (!HTTP_FIELD_NAME.matcher(clientAddressHeader).matches()) {
        throw new IllegalArgumentException("mcp.clientAddressHeader must be an HTTP field-name token");
      }
      return new ServerSettings(port, host, basePath, allowedOrigins, authToken,
          rateLimit, maxBodyBytes, toolTimeoutMs, validationTimeoutMs,
          cancellationGraceMs, maxConcurrentToolCalls, maxConcurrentCallsPerTool,
          maxConcurrentValidations, maxResponseBytes, healthEnabled,
          trustedProxies, clientAddressHeader);
    }

    private static String normalizeBasePath(String value) {
      if (value == null || value.isBlank() || "/".equals(value)) {
        return "";
      }
      String path = value.startsWith("/") ? value : "/" + value;
      path = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
      if (path.contains("//") || path.contains("?") || path.contains("#")
          || path.chars().anyMatch(Character::isWhitespace)) {
        throw new IllegalArgumentException("mcp.basePath must be a simple absolute HTTP path");
      }
      return path;
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

    private static Set<String> parseCsvSet(Object value) {
      if (value == null) return Set.of();
      List<String> values = new ArrayList<>();
      if (value instanceof JsonArray array) {
        array.forEach(item -> values.add(String.valueOf(item).trim()));
      } else {
        for (String item : String.valueOf(value).split(",")) values.add(item.trim());
      }
      values.removeIf(String::isBlank);
      if (values.contains("*")) {
        throw new IllegalArgumentException("mcp.trustedProxies must list explicit addresses");
      }
      return Set.copyOf(values);
    }

    private static boolean booleanValue(JsonObject config, String key, boolean fallback) {
      Object value = config.getValue(key);
      if (value == null) return fallback;
      if (value instanceof Boolean flag) return flag;
      if (value instanceof String text
          && ("true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text))) {
        return Boolean.parseBoolean(text);
      }
      throw new IllegalArgumentException(key + " must be a boolean");
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
