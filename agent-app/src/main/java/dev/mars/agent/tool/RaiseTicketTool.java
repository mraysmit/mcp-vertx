package dev.mars.agent.tool;
import dev.mars.mcp.tool.Tool;

import dev.mars.mcp.tool.AgentContext;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;

import java.util.UUID;
import java.util.logging.Logger;

/**
 * Agent tool that creates a support ticket for a failure that requires
 * manual investigation.
 *
 * <p><b>Tool name:</b> {@code case.raiseTicket}<br>
 * <b>Side effects:</b>
 * <ul>
 *   <li>Generates a unique ticket ID ({@code TICKET-<UUID>}).</li>
 *   <li>Publishes a {@code TicketCreated} event to the injected events
 *       address for observability.</li>
 * </ul>
 *
 * <h2>Expected args</h2>
 * <pre>
 * {
 *   "tradeId":   "T-200",
 *   "category":  "ReferenceData",
 *   "summary":   "Counterparty LEI issue",
 *   "detail":    "Failure reason: LEI not found in registry"
 * }
 * </pre>
 *
 * <h2>Return value</h2>
 * <pre>
 * {
 *   "status":   "created",
 *   "ticketId": "TICKET-...",
 *   "tradeId":  "T-200",
 *   "category": "ReferenceData",
 *   "summary":  "Counterparty LEI issue"
 * }
 * </pre>
 */
public class RaiseTicketTool implements Tool {

  private static final Logger LOG = Logger.getLogger(RaiseTicketTool.class.getName());

  private final Vertx vertx;
  private final String eventsAddress;

  /**
   * @param vertx         the Vert.x instance for event bus access
   * @param eventsAddress the event bus address to publish events to
   */
  public RaiseTicketTool(Vertx vertx, String eventsAddress) {
    this.vertx = vertx;
    this.eventsAddress = eventsAddress;
  }

  @Override
  public String name() {
    return "case.raiseTicket";
  }

  @Override
  public String instructions() {
    return """
        ### case.raiseTicket
        Create a support ticket when the failure requires manual human investigation.
        Always raise a ticket for MEDIUM, HIGH, or CRITICAL severity.
        For LOW severity, a ticket is optional — use judgment based on recurrence risk.
        - `category` must match the case.classify output.
        - `summary` should be one concise sentence (≤ 120 chars) describing the problem.
        - `detail` should include: what was found in lookup, classification reasoning,
          what has already been done (notifications, events published), and suggested next action.
        Raising a ticket is a terminal action — do not continue investigation afterwards.
        """;
  }

  @Override
  public String description() {
    return "Creates a support ticket for a trade failure requiring manual investigation.";
  }

  @Override
  public JsonObject schema() {
    return new JsonObject()
      .put("type", "object")
      .put("properties", new JsonObject()
        .put("tradeId", new JsonObject()
          .put("type", "string")
          .put("description", "The trade identifier"))
        .put("category", new JsonObject()
          .put("type", "string")
          .put("description", "Ticket category, e.g. ReferenceData"))
        .put("summary", new JsonObject()
          .put("type", "string")
          .put("description", "Brief summary of the issue"))
        .put("detail", new JsonObject()
          .put("type", "string")
          .put("description", "Detailed description of the failure")))
      .put("required", new io.vertx.core.json.JsonArray()
        .add("tradeId").add("category").add("summary"));
  }

  @Override
  public Future<JsonObject> invoke(JsonObject args, AgentContext ctx) {
    String ticketId = "TICKET-" + UUID.randomUUID();
    String tradeId = args.getString("tradeId");
    String category = args.getString("category");

    LOG.info("Raising ticket: ticketId=" + ticketId + " tradeId=" + tradeId
        + " category=" + category + " caseId=" + ctx.caseId());

    JsonObject result = new JsonObject()
      .put("status", "created")
      .put("ticketId", ticketId)
      .put("tradeId", tradeId)
      .put("category", category)
      .put("summary", args.getString("summary"));

    JsonObject event = new JsonObject()
      .put("type", "TicketCreated")
      .put("ticketId", ticketId)
      .put("tradeId", tradeId)
      .put("category", category)
      .put("correlationId", ctx.correlationId())
      .put("caseId", ctx.caseId());
    vertx.eventBus().publish(eventsAddress, event);

    LOG.info("Ticket created and TicketCreated event published: ticketId=" + ticketId);
    return Future.succeededFuture(result);
  }
}
