package dev.mars.mcp.testing;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Logs JUnit class and test lifecycle events with stable diagnostic context. */
public final class TestLoggingExtension implements BeforeAllCallback, AfterAllCallback,
    BeforeTestExecutionCallback, TestWatcher {

  private static final Logger LOG = LoggerFactory.getLogger(TestLoggingExtension.class);
  private static final Map<String, Long> STARTED_NANOS = new ConcurrentHashMap<>();

  @Override
  public void beforeAll(ExtensionContext context) {
    LOG.atInfo().log(() -> "TEST CLASS START: class=" + context.getRequiredTestClass().getName());
    LOG.atDebug().log(() -> "Test class context: uniqueId=" + context.getUniqueId()
        + " tags=" + context.getTags());
  }

  @Override
  public void beforeTestExecution(ExtensionContext context) {
    STARTED_NANOS.put(context.getUniqueId(), System.nanoTime());
    LOG.atInfo().log(() -> "TEST START: " + testName(context));
    LOG.atDebug().log(() -> "Test execution context: uniqueId=" + context.getUniqueId()
        + " displayName=\"" + context.getDisplayName() + "\""
        + " tags=" + context.getTags()
        + " thread=" + Thread.currentThread().getName());
  }

  @Override
  public void testSuccessful(ExtensionContext context) {
    LOG.atInfo().log(() -> "TEST PASS: " + testName(context)
        + " durationMs=" + elapsedMillis(context));
  }

  @Override
  public void testFailed(ExtensionContext context, Throwable cause) {
    LOG.error("TEST FAIL: " + testName(context)
        + " durationMs=" + elapsedMillis(context), cause);
  }

  @Override
  public void testAborted(ExtensionContext context, Throwable cause) {
    LOG.warn("TEST ABORTED: " + testName(context)
        + " durationMs=" + elapsedMillis(context), cause);
  }

  @Override
  public void testDisabled(ExtensionContext context, Optional<String> reason) {
    LOG.atInfo().log(() -> "TEST DISABLED: " + testName(context)
        + " reason=\"" + reason.orElse("not supplied") + "\"");
  }

  @Override
  public void afterAll(ExtensionContext context) {
    LOG.atInfo().log(() -> "TEST CLASS END: class=" + context.getRequiredTestClass().getName());
  }

  private static String testName(ExtensionContext context) {
    String className = context.getRequiredTestClass().getSimpleName();
    String methodName = context.getTestMethod().map(Method::getName)
        .orElse(context.getDisplayName());
    return className + "." + methodName;
  }

  private static long elapsedMillis(ExtensionContext context) {
    Long started = STARTED_NANOS.remove(context.getUniqueId());
    return started == null ? -1L : (System.nanoTime() - started) / 1_000_000L;
  }
}
