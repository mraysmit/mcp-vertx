package dev.mars.mcp;

import dev.mars.mcp.testing.TestLoggingExtension;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(TestLoggingExtension.class)
class MainTest {

  private static final String ABSENT_ENVIRONMENT =
      "MCP_VERTX_TEST_ENVIRONMENT_THAT_MUST_NOT_EXIST_9F01A5";

  @Test
  void builds_configuration_from_system_properties() {
    Map<String, String> values = new LinkedHashMap<>();
    values.put("mcp.port", "4312");
    values.put("mcp.maxBodyBytes", "2048");
    values.put("mcp.healthEnabled", "TRUE");
    values.put("mcp.resourceIdField", "tenantId");

    withProperties(values, () -> {
      JsonObject config = Main.configuration();
      assertEquals(4312, config.getInteger("mcp.port"));
      assertEquals(2048L, config.getLong("mcp.maxBodyBytes"));
      assertTrue(config.getBoolean("mcp.healthEnabled"));
      assertEquals("tenantId", Main.resourceIdField());
      assertTrue(config.containsKey("mcp.maxConcurrentToolCalls"));
    });
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
}
