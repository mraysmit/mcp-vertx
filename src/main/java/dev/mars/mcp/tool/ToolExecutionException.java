package dev.mars.mcp.tool;

/** A provider failure whose message is deliberately safe to return to a client. */
public final class ToolExecutionException extends RuntimeException {

  private final String errorType;
  private final boolean retryable;

  public ToolExecutionException(String safeMessage) {
    this("tool_execution_failed", safeMessage, false, null);
  }

  public ToolExecutionException(String errorType, String safeMessage,
                                boolean retryable, Throwable cause) {
    super(requireNonBlank(safeMessage, "safeMessage"), cause);
    this.errorType = requireNonBlank(errorType, "errorType");
    this.retryable = retryable;
  }

  public String errorType() {
    return errorType;
  }

  public boolean retryable() {
    return retryable;
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
