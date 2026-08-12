package dev.mars.mcp.tool;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonObject;

/** Immutable invocation metadata with a cooperative cancellation signal. */
public final class ToolContext {

  private final String correlationId;
  private final String resourceId;
  private final JsonObject metadata;
  private final long deadlineEpochMillis;
  private final Promise<Void> cancellation = Promise.promise();

  public ToolContext(String correlationId, String resourceId, JsonObject metadata) {
    this(correlationId, resourceId, metadata, Long.MAX_VALUE);
  }

  public ToolContext(String correlationId, String resourceId, JsonObject metadata,
                     long deadlineEpochMillis) {
    this.correlationId = requireNonBlank(correlationId, "correlationId");
    this.resourceId = requireNonBlank(resourceId, "resourceId");
    this.metadata = metadata == null ? new JsonObject() : metadata.copy();
    if (deadlineEpochMillis <= 0) {
      throw new IllegalArgumentException("deadlineEpochMillis must be positive");
    }
    this.deadlineEpochMillis = deadlineEpochMillis;
  }

  public String correlationId() {
    return correlationId;
  }

  public String resourceId() {
    return resourceId;
  }

  public JsonObject metadata() {
    return metadata.copy();
  }

  public long deadlineEpochMillis() {
    return deadlineEpochMillis;
  }

  public long remainingTimeMillis() {
    if (deadlineEpochMillis == Long.MAX_VALUE) {
      return Long.MAX_VALUE;
    }
    return Math.max(0, deadlineEpochMillis - System.currentTimeMillis());
  }

  public boolean isCancelled() {
    return cancellation.future().isComplete();
  }

  /** Completes when the server cancels this invocation. */
  public Future<Void> cancellation() {
    return cancellation.future();
  }

  /** Server-side cancellation hook. Providers should observe, not call, this. */
  public boolean cancel() {
    return cancellation.tryComplete();
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
