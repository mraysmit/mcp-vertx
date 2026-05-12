package dev.mars.agent.tool;
import dev.mars.mcp.tool.Tool;

import dev.mars.mcp.tool.AgentContext;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;

import java.util.logging.Logger;

/**
 * Agent tool that publishes an arbitrary domain event to the
 * injected events address.
 *
 * <p><b>Tool name:</b> {@code events.publish}<br>
 * <b>Side effects:</b> publishes the supplied args (enriched with
 * {@code correlationId} and {@code caseId} from the {@link AgentContext})
 * as a fire-and-forget event.
 *
 * <h2>Expected args</h2>
 * Any valid {@code JsonObject}; typically includes a {@code "type"} field
 * (e.g. {@code TradeEscalated}), a {@code tradeId}, and domain details.
 *
 * <h2>Return value</h2>
 * <pre>
 * {
 *   "status": "published",
 *   "event":  { ... the event that was published ... }
 * }
 * </pre>
 */
public class PublishEventTool implements Tool {

  private static final Logger LOG = Logger.getLogger(PublishEventTool.class.getName());

  private final Vertx vertx;
  private final String eventsAddress;

  /**
   * @param vertx         the Vert.x instance for event bus access
   * @param eventsAddress the event bus address to publish events to
   */
  public PublishEventTool(Vertx vertx, String eventsAddress) {
    this.vertx = vertx;
    this.eventsAddress = eventsAddress;
  }

  @Override
  public String name() {
    return "events.publish";
  }

  @Override
  public String instructions() {
    return """
        ### events.publish
        Emit a domain event so downstream systems can react autonomously.
        Use these event types based on the situation:
        - `TradeEscalated`    — general escalation for HIGH severity failures
        - `ComplianceHold`    — REQUIRED for any sanctions or AML issue (CRITICAL)
        - `CreditReview`      — REQUIRED when a counterparty credit event is detected
        - `RegulatoryBreach`  — REQUIRED when a regulatory reporting deadline is missed
        - `DuplicateSuspect`  — when a probable duplicate trade is detected
        - `SettlementFailed`  — when a settlement instruction has definitively failed
        Always include `tradeId` and a `reason` field in the event payload.
        Publish events BEFORE raising tickets so downstream systems are notified promptly.
        Publishing an event is a terminal action for compliance/credit events.
        """;
  }

  @Override
  public String description() {
    return "Publishes a domain event to the event bus for downstream consumers.";
  }

  @Override
  public JsonObject schema() {
    return new JsonObject()
      .put("type", "object")
      .put("properties", new JsonObject()
        .put("type", new JsonObject()
          .put("type", "string")
          .put("description", "The event type, e.g. TradeEscalated"))
        .put("tradeId", new JsonObject()
          .put("type", "string")
          .put("description", "The trade identifier"))
        .put("reason", new JsonObject()
          .put("type", "string")
          .put("description", "The reason for the event")))
      .put("required", new io.vertx.core.json.JsonArray().add("type"));
  }

  @Override
  public Future<JsonObject> invoke(JsonObject args, AgentContext ctx) {
    LOG.info("Publishing event: type=" + args.getString("type")
        + " caseId=" + ctx.caseId() + " correlationId=" + ctx.correlationId());

    JsonObject event = args.copy()
      .put("correlationId", ctx.correlationId())
      .put("caseId", ctx.caseId());

    vertx.eventBus().publish(eventsAddress, event);

    LOG.info("Event published to " + eventsAddress + ": type=" + event.getString("type"));
    return Future.succeededFuture(new JsonObject()
      .put("status", "published")
      .put("event", event));
  }
}
