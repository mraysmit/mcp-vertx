package dev.mars.agent.tool;

import dev.mars.mcp.tool.AgentContext;
import dev.mars.mcp.tool.Tool;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.UUID;
import java.util.logging.Logger;

/**
 * Agent tool that simulates sending a notification (email, Symphony, PagerDuty)
 * to the appropriate team based on the failure classification.
 *
 * <p><b>Tool name:</b> {@code comms.notify}<br>
 * <b>Side effects:</b> publishes a {@code NotificationSent} event to the
 * events address.
 *
 * <h2>Why this matters</h2>
 * After classifying a failure, a real LLM decides who to notify:
 * <em>"This is a HIGH severity ReferenceData issue — I should page the
 * Reference Data team and CC the Trade Operations."</em> The notification
 * channel and urgency depend on the classification from the prior step.
 *
 * <h2>Expected args</h2>
 * <pre>
 * {
 *   "tradeId":  "T-300",
 *   "channel":  "email",             // email | Symphony | pagerduty
 *   "team":     "ReferenceData",
 *   "subject":  "HIGH: LEI missing for trade T-300",
 *   "body":     "Counterparty LEI is missing..."
 * }
 * </pre>
 *
 * <h2>Return value</h2>
 * <pre>
 * {
 *   "status":         "sent",
 *   "notificationId": "NOTIF-...",
 *   "channel":        "email",
 *   "team":           "ReferenceData",
 *   "tradeId":        "T-300",
 *   "subject":        "HIGH: LEI missing for trade T-300",
 *   "body":           "Counterparty LEI is missing..."  // omitted if blank
 * }
 * </pre>
 */
public class NotifyTool implements Tool {

  private static final Logger LOG = Logger.getLogger(NotifyTool.class.getName());

  private final Vertx vertx;
  private final String eventsAddress;

  public NotifyTool(Vertx vertx, String eventsAddress) {
    this.vertx = vertx;
    this.eventsAddress = eventsAddress;
  }

  @Override
  public String name() {
    return "comms.notify";
  }

  @Override
  public String instructions() {
    return """
        ### comms.notify
        Alert the appropriate team after classification. Match channel to severity:
        - LOW / MEDIUM → `email`   — team: Operations or ReferenceData
        - HIGH         → `Symphony`   — team: TradingDesk or SeniorOperations
        - CRITICAL     → `pagerduty` — team: RiskManagement or Compliance
        Subject format: `<SEVERITY>: <brief issue> for trade <tradeId>`
        Body should include: trade ID, failure reason, classification category, and next steps.
        Always send a notification before or alongside raising a ticket.
        """;
  }

  @Override
  public String description() {
    return "Sends a notification to the appropriate team via email, Symphony, or PagerDuty.";
  }

  @Override
  public JsonObject schema() {
    return new JsonObject()
        .put("type", "object")
        .put("properties", new JsonObject()
            .put("tradeId", new JsonObject()
                .put("type", "string")
                .put("description", "The trade identifier"))
            .put("channel", new JsonObject()
                .put("type", "string")
                .put("enum", new JsonArray().add("email").add("Symphony").add("pagerduty"))
                .put("description", "Notification channel"))
            .put("team", new JsonObject()
                .put("type", "string")
                .put("description", "Target team, e.g. ReferenceData, Operations, Compliance"))
            .put("subject", new JsonObject()
                .put("type", "string")
                .put("description", "Notification subject/title"))
            .put("body", new JsonObject()
                .put("type", "string")
                .put("description", "Notification body text")))
        .put("required", new JsonArray().add("tradeId").add("channel").add("team").add("subject"));
  }

  @Override
  public Future<JsonObject> invoke(JsonObject args, AgentContext ctx) {
    String tradeId = args.getString("tradeId", ctx.caseId());
    String channel = args.getString("channel", "email");
    String team = args.getString("team", "Operations");
    String subject = args.getString("subject", "Trade failure notification");
    String body = args.getString("body", "");
    String notifId = "NOTIF-" + UUID.randomUUID().toString().substring(0, 8);

    LOG.info("Sending notification: id=" + notifId + " channel=" + channel
        + " team=" + team + " tradeId=" + tradeId);

    JsonObject result = new JsonObject()
        .put("status", "sent")
        .put("notificationId", notifId)
        .put("channel", channel)
        .put("team", team)
        .put("tradeId", tradeId)
        .put("subject", subject);
    if (body != null && !body.isEmpty()) {
      result.put("body", body);
    }

    // Publish a NotificationSent event
    JsonObject event = new JsonObject()
        .put("type", "NotificationSent")
        .put("notificationId", notifId)
        .put("channel", channel)
        .put("team", team)
        .put("subject", subject)
        .put("tradeId", tradeId)
        .put("correlationId", ctx.correlationId())
        .put("caseId", ctx.caseId());
    vertx.eventBus().publish(eventsAddress, event);

    LOG.info("Notification sent: " + notifId + " via " + channel + " to " + team);
    return Future.succeededFuture(result);
  }
}
