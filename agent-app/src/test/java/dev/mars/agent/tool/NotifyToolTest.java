package dev.mars.agent.tool;

import dev.mars.mcp.tool.AgentContext;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(VertxExtension.class)
class NotifyToolTest {

  private AgentContext testCtx() {
    return new AgentContext("corr-1", "case-1", new JsonObject());
  }

  @Test
  void name_is_comms_notify() {
    var tool = new NotifyTool(Vertx.vertx(), "events.test");
    assertEquals("comms.notify", tool.name());
  }

  @Test
  void description_is_not_empty() {
    var tool = new NotifyTool(Vertx.vertx(), "events.test");
    assertFalse(tool.description().isBlank());
  }

  @Test
  void schema_declares_required_properties() {
    var tool = new NotifyTool(Vertx.vertx(), "events.test");
    var schema = tool.schema();
    assertEquals("object", schema.getString("type"));
    var props = schema.getJsonObject("properties");
    assertNotNull(props.getJsonObject("tradeId"));
    assertNotNull(props.getJsonObject("channel"));
    assertNotNull(props.getJsonObject("team"));
    assertNotNull(props.getJsonObject("subject"));
    var required = schema.getJsonArray("required");
    assertTrue(required.contains("tradeId"));
    assertTrue(required.contains("channel"));
    assertTrue(required.contains("team"));
    assertTrue(required.contains("subject"));
  }

  @Test
  void invoke_returns_notification_result(Vertx vertx, VertxTestContext ctx) {
    var tool = new NotifyTool(vertx, "events.notify-test");
    var args = new JsonObject()
        .put("tradeId", "T-1")
        .put("channel", "Symphony")
        .put("team", "Operations")
        .put("subject", "Trade failure needs attention")
        .put("body", "Please review trade T-1");

    tool.invoke(args, testCtx()).onSuccess(result -> {
      assertEquals("sent", result.getString("status"));
      assertTrue(result.getString("notificationId").startsWith("NOTIF-"));
      assertEquals("Symphony", result.getString("channel"));
      assertEquals("Operations", result.getString("team"));
      assertEquals("T-1", result.getString("tradeId"));
      ctx.completeNow();
    }).onFailure(ctx::failNow);
  }

  @Test
  void invoke_publishes_notification_sent_event(Vertx vertx, VertxTestContext ctx) {
    vertx.eventBus().consumer("events.notify-evt", msg -> {
      JsonObject event = (JsonObject) msg.body();
      assertEquals("NotificationSent", event.getString("type"));
      assertEquals("T-2", event.getString("tradeId"));
      assertEquals("email", event.getString("channel"));
      assertEquals("RiskTeam", event.getString("team"));
      ctx.completeNow();
    });

    var tool = new NotifyTool(vertx, "events.notify-evt");
    var args = new JsonObject()
        .put("tradeId", "T-2")
        .put("channel", "email")
        .put("team", "RiskTeam")
        .put("subject", "Test notification");

    tool.invoke(args, testCtx());
  }
}
