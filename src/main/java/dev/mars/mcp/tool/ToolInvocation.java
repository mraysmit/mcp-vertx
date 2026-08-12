package dev.mars.mcp.tool;

import io.vertx.core.Future;

import java.util.Objects;
import java.util.function.Supplier;

/** A tool result future paired with cooperative cancellation work. */
public record ToolInvocation(
    Future<ToolResult> result,
    Supplier<Future<Void>> cancellation) {

  public ToolInvocation {
    Objects.requireNonNull(result, "result");
    Objects.requireNonNull(cancellation, "cancellation");
  }

  public static ToolInvocation of(Future<ToolResult> result) {
    return new ToolInvocation(result, Future::succeededFuture);
  }

  public Future<Void> cancel() {
    try {
      Future<Void> future = cancellation.get();
      return future == null
          ? Future.failedFuture("Tool cancellation returned a null Future")
          : future;
    } catch (RuntimeException error) {
      return Future.failedFuture(error);
    }
  }
}
