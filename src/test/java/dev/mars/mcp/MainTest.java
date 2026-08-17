package dev.mars.mcp;

import dev.mars.a2a.A2aAgent;
import dev.mars.mcp.testing.TestLoggingExtension;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.EventKind;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(TestLoggingExtension.class)
class MainTest {

  private static final String ABSENT_ENVIRONMENT =
      "MCP_VERTX_TEST_ENVIRONMENT_THAT_MUST_NOT_EXIST_9F01A5";

  @Test
  void configures_dedicated_debug_logging_for_both_protocol_transports()
      throws IOException {
    try (var stream = Main.class.getResourceAsStream("/logback.xml")) {
      assertNotNull(stream);
      String configuration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(configuration.contains(
          "<logger name=\"dev.mars.mcp\" level=\"${MCP_LOG_LEVEL:-DEBUG}\"/>"));
      assertTrue(configuration.contains(
          "<logger name=\"dev.mars.a2a\" level=\"${A2A_LOG_LEVEL:-DEBUG}\"/>"));
    }
  }

  @Test
  void builds_configuration_from_system_properties() {
    Map<String, String> values = new LinkedHashMap<>();
    values.put("mcp.port", "4312");
    values.put("mcp.maxBodyBytes", "2048");
    values.put("mcp.healthEnabled", "TRUE");
    values.put("mcp.resourceIdField", "tenantId");
    values.put("a2a.enabled", "true");
    values.put("a2a.port", "4313");
    values.put("a2a.basePath", "/agents/example");

    withProperties(values, () -> {
      JsonObject config = Main.configuration();
      assertEquals(4312, config.getInteger("mcp.port"));
      assertEquals(2048L, config.getLong("mcp.maxBodyBytes"));
      assertTrue(config.getBoolean("mcp.healthEnabled"));
      assertEquals("tenantId", Main.resourceIdField());
      assertTrue(config.containsKey("mcp.maxConcurrentToolCalls"));
      assertTrue(config.getBoolean("a2a.enabled"));
      assertEquals(4313, config.getInteger("a2a.port"));
      assertEquals("/agents/example", config.getString("a2a.basePath"));
    });
  }

  @Test
  void enables_a2a_only_with_exactly_one_agent_provider() {
    A2aAgent agent = new StubAgent();
    JsonObject disabled = new JsonObject().put("a2a.enabled", false);
    JsonObject enabled = new JsonObject().put("a2a.enabled", true);

    assertNull(Main.selectA2aAgent(disabled, java.util.List.of(agent)));
    assertSame(agent, Main.selectA2aAgent(enabled, java.util.List.of(agent)));
    assertThrows(IllegalStateException.class,
        () -> Main.selectA2aAgent(enabled, java.util.List.of()));
    assertThrows(IllegalStateException.class,
        () -> Main.selectA2aAgent(enabled, java.util.List.of(agent, new StubAgent())));
  }

  @Test
  void setting_helpers_support_fallbacks_and_reject_bad_values() {
    String property = "mcp.vertx.test.setting";
    System.clearProperty(property);
    assertEquals("fallback", Main.setting(property, ABSENT_ENVIRONMENT, "fallback"));
    System.setProperty(property, "value");
    try {
      assertEquals("value", Main.setting(property, ABSENT_ENVIRONMENT, "fallback"));
      System.setProperty(property, " ");
      assertEquals("fallback", Main.setting(property, ABSENT_ENVIRONMENT, "fallback"));
    } finally {
      System.clearProperty(property);
    }

    withProperty(property, "17",
        () -> assertEquals(17, Main.integerSetting(property, ABSENT_ENVIRONMENT, 1)));
    withProperty(property, "922337203685477580",
        () -> assertEquals(922337203685477580L,
            Main.longSetting(property, ABSENT_ENVIRONMENT, 1L)));
    withProperty(property, "false",
        () -> assertFalse(Main.booleanSetting(property, ABSENT_ENVIRONMENT, true)));
    withProperty(property, "not-an-integer",
        () -> assertThrows(IllegalArgumentException.class,
            () -> Main.integerSetting(property, ABSENT_ENVIRONMENT, 1)));
    withProperty(property, "not-a-long",
        () -> assertThrows(IllegalArgumentException.class,
            () -> Main.longSetting(property, ABSENT_ENVIRONMENT, 1L)));
    withProperty(property, "sometimes",
        () -> assertThrows(IllegalArgumentException.class,
            () -> Main.booleanSetting(property, ABSENT_ENVIRONMENT, false)));
  }

  @Test
  void configures_vertx_to_use_slf4j_when_no_delegate_is_selected() {
    String previous = System.getProperty(Main.VERTX_LOGGER_FACTORY_PROPERTY);
    System.clearProperty(Main.VERTX_LOGGER_FACTORY_PROPERTY);
    try {
      Main.configureVertxLogging();
      assertEquals(Main.SLF4J_LOGGER_FACTORY,
          System.getProperty(Main.VERTX_LOGGER_FACTORY_PROPERTY));
    } finally {
      restoreProperty(Main.VERTX_LOGGER_FACTORY_PROPERTY, previous);
    }
  }

  @Test
  void preserves_an_explicit_vertx_logging_delegate() {
    String previous = System.getProperty(Main.VERTX_LOGGER_FACTORY_PROPERTY);
    System.setProperty(Main.VERTX_LOGGER_FACTORY_PROPERTY, "example.CustomLoggerFactory");
    try {
      Main.configureVertxLogging();
      assertEquals("example.CustomLoggerFactory",
          System.getProperty(Main.VERTX_LOGGER_FACTORY_PROPERTY));
    } finally {
      restoreProperty(Main.VERTX_LOGGER_FACTORY_PROPERTY, previous);
    }
  }

  private void withProperties(Map<String, String> values, Runnable assertion) {
    Map<String, String> previous = new LinkedHashMap<>();
    values.forEach((key, value) -> {
      previous.put(key, System.getProperty(key));
      System.setProperty(key, value);
    });
    try {
      assertion.run();
    } finally {
      previous.forEach((key, value) -> {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
      });
    }
  }

  private void withProperty(String key, String value, Runnable assertion) {
    String previous = System.getProperty(key);
    System.setProperty(key, value);
    try {
      assertion.run();
    } finally {
      if (previous == null) System.clearProperty(key);
      else System.setProperty(key, previous);
    }
  }

  private void restoreProperty(String key, String value) {
    if (value == null) System.clearProperty(key);
    else System.setProperty(key, value);
  }

  private static final class StubAgent implements A2aAgent {
    @Override public AgentCard agentCard() { return null; }
    @Override public Future<EventKind> sendMessage(MessageSendParams params) {
      return Future.failedFuture("not used");
    }
  }
}
