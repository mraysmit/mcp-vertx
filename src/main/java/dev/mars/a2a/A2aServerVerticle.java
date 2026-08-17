package dev.mars.a2a;

import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.LoggerFormat;
import io.vertx.ext.web.handler.LoggerHandler;
import org.a2aproject.sdk.spec.AgentCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;

/** A2A v1.0 HTTP+JSON transport hosted independently from the MCP listener. */
public final class A2aServerVerticle extends VerticleBase {

  static final String PROTOCOL_VERSION = "1.0";
  static final String AGENT_CARD_PATH = "/.well-known/agent-card.json";
  static final String MEDIA_TYPE = "application/a2a+json";
  private static final int DEFAULT_PORT = 3002;
  private static final String DEFAULT_HOST = "127.0.0.1";
  private static final Logger LOG = LoggerFactory.getLogger(A2aServerVerticle.class);

  private final A2aAgent agent;
  private final A2aTaskStore taskStore;
  private final A2aJsonCodec json = new A2aJsonCodec();
  private volatile int actualPort = -1;
  private volatile String authToken = "";

  public A2aServerVerticle(A2aAgent agent) {
    this(agent, new InMemoryA2aTaskStore());
  }

  public A2aServerVerticle(A2aAgent agent, A2aTaskStore taskStore) {
    this.agent = Objects.requireNonNull(agent, "agent");
    this.taskStore = Objects.requireNonNull(taskStore, "taskStore");
    AgentCard card = Objects.requireNonNull(agent.agentCard(), "agent.agentCard()");
    LOG.atInfo().log(() -> "A2A server initialized: agent=\"" + card.name()
        + "\" version=" + card.version());
  }

  int actualPort() {
    return actualPort;
  }

  @Override
  public Future<?> start() {
    String host = config().getString("a2a.host", DEFAULT_HOST);
    int port = config().getInteger("a2a.port", DEFAULT_PORT);
    authToken = config().getString("a2a.authToken", "");
    if (!isLoopback(host) && authToken.isBlank()) {
      return Future.failedFuture(
          "a2a.authToken is required when a2a.host is not a loopback address");
    }
    AgentCard card = agent.agentCard();
    LOG.atInfo().log(() -> "Starting A2A HTTP server: host=" + host
        + " configuredPort=" + port + " discovery=\"" + AGENT_CARD_PATH
        + "\" protocol=" + PROTOCOL_VERSION);

    Router router = Router.router(vertx);
    router.route().handler(LoggerHandler.create(LoggerFormat.CUSTOM)
        .customFormatter(this::formatAccessLog));
    router.get(AGENT_CARD_PATH).handler(context -> sendAgentCard(context, card));
    String basePath = normalizedBasePath(config().getString("a2a.basePath", "/a2a"));
    router.route(basePath + "/*").handler(this::enforceAuthentication);
    String sendPath = basePath + "/message:send";
    String sendPattern = "^" + java.util.regex.Pattern.quote(sendPath) + "$";
    router.postWithRegex(sendPattern).handler(this::validateProtocolVersion);
    router.postWithRegex(sendPattern)
        .handler(BodyHandler.create(false)
            .setBodyLimit(config().getLong("a2a.maxBodyBytes", 1_048_576L))
            .setMergeFormAttributes(false));
    router.postWithRegex(sendPattern).handler(this::sendMessage);
    String streamPath = basePath + "/message:stream";
    String streamPattern = "^" + java.util.regex.Pattern.quote(streamPath) + "$";
    router.postWithRegex(streamPattern).handler(this::validateProtocolVersion);
    router.postWithRegex(streamPattern)
        .handler(BodyHandler.create(false)
            .setBodyLimit(config().getLong("a2a.maxBodyBytes", 1_048_576L))
            .setMergeFormAttributes(false));
    router.postWithRegex(streamPattern).handler(this::sendStreamingMessage);
    String taskPath = basePath + "/tasks/:id";
    String tasksPath = basePath + "/tasks";
    router.get(tasksPath).handler(this::validateProtocolVersion);
    router.get(tasksPath).handler(this::listTasks);
    String cancelPattern = "^" + java.util.regex.Pattern.quote(basePath)
        + "/tasks/[^/]+:cancel$";
    router.postWithRegex(cancelPattern).handler(this::validateProtocolVersion);
    router.postWithRegex(cancelPattern)
        .handler(BodyHandler.create(false)
            .setBodyLimit(config().getLong("a2a.maxBodyBytes", 1_048_576L))
            .setMergeFormAttributes(false));
    router.postWithRegex(cancelPattern).handler(context -> cancelTask(context, basePath));
    String subscribePattern = "^" + java.util.regex.Pattern.quote(basePath)
        + "/tasks/[^/]+:subscribe$";
    router.getWithRegex(subscribePattern).handler(this::validateProtocolVersion);
    router.getWithRegex(subscribePattern)
        .handler(context -> subscribeToTask(context, basePath));
    router.get(taskPath).handler(this::validateProtocolVersion);
    router.get(taskPath).handler(this::getTask);

    return vertx.createHttpServer()
        .requestHandler(router)
        .listen(port, host)
        .map(server -> {
          actualPort = server.actualPort();
          LOG.info("A2A server started on " + host + ":" + actualPort
              + " (discovery=\"" + AGENT_CARD_PATH + "\", protocol="
              + PROTOCOL_VERSION + ")");
          return null;
        });
  }

  @Override
  public Future<?> stop() {
    LOG.atInfo().log(() -> "Stopping A2A server: port=" + actualPort);
    actualPort = -1;
    LOG.info("A2A server stopped");
    return Future.succeededFuture();
  }

  private void sendAgentCard(RoutingContext context, AgentCard card) {
    LOG.atDebug().log(() -> "Serving A2A Agent Card: agent=\"" + card.name()
        + "\" remote=" + context.request().remoteAddress());
    context.response()
        .putHeader("Content-Type", "application/json")
        .end(json.encodeAgentCard(card));
  }

  private void enforceAuthentication(RoutingContext context) {
    if (authToken.isBlank()) {
      context.next();
      return;
    }
    String expected = "Bearer " + authToken;
    String supplied = context.request().getHeader("Authorization");
    if (!constantTimeEquals(expected, supplied)) {
      LOG.atInfo().log(() -> "Rejected unauthenticated A2A request: method="
          + context.request().method() + " path=\"" + context.request().path()
          + "\" remote=" + context.request().remoteAddress());
      context.response().setStatusCode(401)
          .putHeader("WWW-Authenticate", "Bearer")
          .putHeader("Content-Type", MEDIA_TYPE)
          .end(new io.vertx.core.json.JsonObject()
              .put("error", new io.vertx.core.json.JsonObject()
                  .put("code", 401)
                  .put("status", "UNAUTHENTICATED")
                  .put("message", "Authentication required"))
              .encode());
      return;
    }
    LOG.atDebug().log(() -> "Accepted authenticated A2A request: method="
        + context.request().method() + " path=\"" + context.request().path() + "\"");
    context.next();
  }

  private static boolean constantTimeEquals(String expected, String supplied) {
    if (supplied == null) return false;
    return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
        supplied.getBytes(StandardCharsets.UTF_8));
  }

  private static boolean isLoopback(String host) {
    return "127.0.0.1".equals(host) || "::1".equals(host)
        || "localhost".equalsIgnoreCase(host);
  }

  private void sendMessage(RoutingContext context) {
    String requestBody = context.body().asString();
    LOG.atDebug().log(() -> "Handling A2A SendMessage: bytes="
        + requestBody.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
        + " remote=" + context.request().remoteAddress());
    final org.a2aproject.sdk.spec.MessageSendParams params;
    try {
      params = json.decodeSendMessage(requestBody);
    } catch (IllegalArgumentException error) {
      LOG.atInfo().log(() -> "Rejected invalid A2A SendMessage request: " + error.getMessage());
      sendError(context, 400, "INVALID_ARGUMENT", error.getMessage(),
          "INVALID_PARAMS", new io.vertx.core.json.JsonObject());
      return;
    }

    agent.sendMessage(params)
        .onSuccess(event -> {
          if (event instanceof org.a2aproject.sdk.spec.Task task) {
            taskStore.save(task);
          }
          LOG.atDebug().log(() -> "Completed A2A SendMessage: responseType="
              + event.getClass().getSimpleName());
          context.response().putHeader("Content-Type", MEDIA_TYPE)
              .end(json.encodeSendMessageResponse(event));
        })
        .onFailure(error -> {
          LOG.warn("A2A SendMessage execution failed", error);
          sendError(context, 500, "INTERNAL", "Agent execution failed",
              "INTERNAL_ERROR", new io.vertx.core.json.JsonObject());
        });
  }

  private void sendStreamingMessage(RoutingContext context) {
    final org.a2aproject.sdk.spec.MessageSendParams params;
    try {
      params = json.decodeSendMessage(context.body().asString());
    } catch (IllegalArgumentException error) {
      sendError(context, 400, "INVALID_ARGUMENT", error.getMessage(),
          "INVALID_PARAMS", new io.vertx.core.json.JsonObject());
      return;
    }

    io.vertx.core.http.HttpServerResponse response = context.response()
        .setChunked(true)
        .putHeader("Content-Type", "text/event-stream")
        .putHeader("Cache-Control", "no-cache")
        .putHeader("X-Accel-Buffering", "no");
    LOG.atDebug().log(() -> "Starting A2A SendStreamingMessage: remote="
        + context.request().remoteAddress());
    agent.streamMessage(params, event -> {
      try {
        String frame = "data: " + json.encodeStreamResponse(event) + "\n\n";
        LOG.atDebug().log(() -> "Writing A2A stream event: type="
            + event.getClass().getSimpleName());
        return taskStore.apply(event).compose(ignored -> response.write(frame));
      } catch (RuntimeException error) {
        return Future.failedFuture(error);
      }
    }).onSuccess(ignored -> {
      LOG.debug("Completed A2A SendStreamingMessage");
      response.end();
    }).onFailure(error -> {
      LOG.warn("A2A SendStreamingMessage failed", error);
      if (!response.headWritten()) {
        sendError(context, 500, "INTERNAL", "Agent streaming failed",
            "INTERNAL_ERROR", new io.vertx.core.json.JsonObject());
      } else {
        response.end();
      }
    });
  }

  private void getTask(RoutingContext context) {
    String taskId = context.pathParam("id");
    taskStore.get(taskId).ifPresentOrElse(task -> {
      LOG.atDebug().log(() -> "Serving A2A task: taskId=" + taskId
          + " state=" + task.status().state());
      context.response().putHeader("Content-Type", MEDIA_TYPE)
          .end(json.encodeTask(task));
    }, () -> {
      LOG.atInfo().log(() -> "A2A task not found: taskId=" + taskId);
      sendError(context, 404, "NOT_FOUND",
          "The specified task does not exist or is not accessible",
          "TASK_NOT_FOUND", new io.vertx.core.json.JsonObject().put("taskId", taskId));
    });
  }

  private void listTasks(RoutingContext context) {
    try {
      org.a2aproject.sdk.spec.ListTasksParams.Builder query =
          org.a2aproject.sdk.spec.ListTasksParams.builder();
      String contextId = context.request().getParam("contextId");
      String status = context.request().getParam("status");
      String pageSize = context.request().getParam("pageSize");
      String pageToken = context.request().getParam("pageToken");
      String historyLength = context.request().getParam("historyLength");
      String after = context.request().getParam("statusTimestampAfter");
      String includeArtifacts = context.request().getParam("includeArtifacts");
      if (contextId != null) query.contextId(contextId);
      if (status != null) {
        org.a2aproject.sdk.spec.TaskState state =
            org.a2aproject.sdk.spec.TaskState.valueOf(status);
        if (state == org.a2aproject.sdk.spec.TaskState.UNRECOGNIZED) {
          throw new IllegalArgumentException("status is not recognized");
        }
        query.status(state);
      }
      if (pageSize != null) {
        int value = Integer.parseInt(pageSize);
        if (value < 1 || value > 100) {
          throw new IllegalArgumentException("pageSize must be between 1 and 100");
        }
        query.pageSize(value);
      }
      if (pageToken != null) query.pageToken(pageToken);
      if (historyLength != null) {
        int value = Integer.parseInt(historyLength);
        if (value < 0) throw new IllegalArgumentException("historyLength must be non-negative");
        query.historyLength(value);
      }
      if (after != null) query.statusTimestampAfter(java.time.Instant.parse(after));
      if (includeArtifacts != null) {
        if (!"true".equalsIgnoreCase(includeArtifacts)
            && !"false".equalsIgnoreCase(includeArtifacts)) {
          throw new IllegalArgumentException("includeArtifacts must be true or false");
        }
        query.includeArtifacts(Boolean.parseBoolean(includeArtifacts));
      }
      A2aTaskPage page = taskStore.list(query.build());
      LOG.atDebug().log(() -> "Listed A2A tasks: returned=" + page.tasks().size()
          + " total=" + page.totalSize());
      context.response().putHeader("Content-Type", MEDIA_TYPE)
          .end(json.encodeTaskPage(page));
    } catch (IllegalArgumentException error) {
      LOG.atInfo().log(() -> "Rejected invalid A2A ListTasks query: "
          + error.getMessage());
      sendError(context, 400, "INVALID_ARGUMENT", error.getMessage(),
          "INVALID_PARAMS", new io.vertx.core.json.JsonObject());
    }
  }

  private void cancelTask(RoutingContext context, String basePath) {
    String prefix = basePath + "/tasks/";
    String path = context.request().path();
    String taskId = path.substring(prefix.length(), path.length() - ":cancel".length());
    taskStore.get(taskId).ifPresentOrElse(task -> {
      if (task.status().state().isFinal()) {
        sendError(context, 400, "FAILED_PRECONDITION",
            "The specified task is not cancelable", "TASK_NOT_CANCELABLE",
            new io.vertx.core.json.JsonObject().put("taskId", taskId));
        return;
      }
      LOG.atDebug().log(() -> "Canceling A2A task: taskId=" + taskId
          + " state=" + task.status().state());
      agent.cancelTask(task).onSuccess(canceled -> {
        if (!taskId.equals(canceled.id())) {
          sendError(context, 500, "INTERNAL",
              "Agent returned a different task from cancellation", "INTERNAL_ERROR",
              new io.vertx.core.json.JsonObject().put("taskId", taskId));
          return;
        }
        taskStore.save(canceled);
        context.response().putHeader("Content-Type", MEDIA_TYPE)
            .end(json.encodeTask(canceled));
      }).onFailure(error -> {
        LOG.warn("A2A CancelTask execution failed: taskId=" + taskId, error);
        String reason = error instanceof UnsupportedOperationException
            ? "UNSUPPORTED_OPERATION" : "INTERNAL_ERROR";
        int code = error instanceof UnsupportedOperationException ? 501 : 500;
        String status = code == 501 ? "UNIMPLEMENTED" : "INTERNAL";
        sendError(context, code, status,
            code == 501 ? error.getMessage() : "Agent cancellation failed", reason,
            new io.vertx.core.json.JsonObject().put("taskId", taskId));
      });
    }, () -> sendError(context, 404, "NOT_FOUND",
        "The specified task does not exist or is not accessible", "TASK_NOT_FOUND",
        new io.vertx.core.json.JsonObject().put("taskId", taskId)));
  }

  private void subscribeToTask(RoutingContext context, String basePath) {
    String prefix = basePath + "/tasks/";
    String path = context.request().path();
    String taskId = path.substring(prefix.length(), path.length() - ":subscribe".length());
    java.util.Optional<org.a2aproject.sdk.spec.Task> current = taskStore.get(taskId);
    if (current.isEmpty()) {
      sendError(context, 404, "NOT_FOUND",
          "The specified task does not exist or is not accessible", "TASK_NOT_FOUND",
          new io.vertx.core.json.JsonObject().put("taskId", taskId));
      return;
    }
    if (!agent.agentCard().capabilities().streaming()
        || current.get().status().state().isFinal()) {
      sendError(context, 400, "FAILED_PRECONDITION",
          "Task subscription is not supported", "UNSUPPORTED_OPERATION",
          new io.vertx.core.json.JsonObject().put("taskId", taskId));
      return;
    }

    io.vertx.core.http.HttpServerResponse response = context.response()
        .setChunked(true)
        .putHeader("Content-Type", "text/event-stream")
        .putHeader("Cache-Control", "no-cache")
        .putHeader("X-Accel-Buffering", "no");
    java.util.concurrent.atomic.AtomicReference<A2aTaskSubscription> registered =
        new java.util.concurrent.atomic.AtomicReference<>();
    A2aTaskSubscription subscription = taskStore.subscribe(taskId, event -> {
      Future<Void> write = response.write(
          "data: " + json.encodeStreamResponse(event) + "\n\n");
      if (event instanceof org.a2aproject.sdk.spec.TaskStatusUpdateEvent update
          && update.status().state().isFinal()) {
        return write.compose(ignored -> {
          A2aTaskSubscription active = registered.get();
          if (active != null) active.close();
          return response.end();
        });
      }
      return write;
    });
    registered.set(subscription);
    response.closeHandler(ignored -> subscription.close());
    LOG.atDebug().log(() -> "Subscribed to A2A task: taskId=" + taskId);
    response.write("data: " + json.encodeStreamResponse(subscription.snapshot()) + "\n\n")
        .onFailure(error -> {
          subscription.close();
          response.end();
        });
  }

  private void validateProtocolVersion(RoutingContext context) {
    String requested = context.request().getHeader("A2A-Version");
    if (requested == null || requested.isBlank()) {
      requested = context.request().getParam("A2A-Version");
    }
    if (requested == null || requested.isBlank()) requested = "0.3";
    if (!PROTOCOL_VERSION.equals(requested)) {
      String rejected = requested;
      LOG.atInfo().log(() -> "Rejected unsupported A2A protocol version: requested="
          + rejected + " supported=" + PROTOCOL_VERSION);
      sendError(context, 400, "INVALID_ARGUMENT",
          "The requested A2A protocol version " + requested + " is not supported",
          "VERSION_NOT_SUPPORTED", new io.vertx.core.json.JsonObject()
              .put("requestedVersion", requested)
              .put("supportedVersions", PROTOCOL_VERSION));
      return;
    }
    context.next();
  }

  private void sendError(RoutingContext context, int code, String status, String message,
                         String reason, io.vertx.core.json.JsonObject metadata) {
    io.vertx.core.json.JsonObject detail = new io.vertx.core.json.JsonObject()
        .put("@type", "type.googleapis.com/google.rpc.ErrorInfo")
        .put("reason", reason)
        .put("domain", "a2a-protocol.org")
        .put("metadata", metadata);
    io.vertx.core.json.JsonObject error = new io.vertx.core.json.JsonObject()
        .put("code", code)
        .put("status", status)
        .put("message", message)
        .put("details", new io.vertx.core.json.JsonArray().add(detail));
    context.response().setStatusCode(code).putHeader("Content-Type", MEDIA_TYPE)
        .end(new io.vertx.core.json.JsonObject().put("error", error).encode());
  }

  private String normalizedBasePath(String value) {
    if (value == null || value.isBlank() || "/".equals(value)) return "";
    String path = value.startsWith("/") ? value : "/" + value;
    return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
  }

  private String formatAccessLog(RoutingContext context, long requestTime) {
    return "A2A access: method=" + context.request().method()
        + " path=\"" + context.request().path() + "\""
        + " status=" + context.response().getStatusCode()
        + " durationMs=" + requestTime;
  }
}
