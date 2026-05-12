package dev.mars.agent.tool;

import dev.mars.mcp.tool.AgentContext;
import dev.mars.mcp.tool.Tool;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.logging.Logger;

/**
 * Agent tool that simulates classifying a trade failure based on
 * gathered data — the reasoning step between lookup and action.
 *
 * <p><b>Tool name:</b> {@code case.classify}<br>
 * <b>Side effects:</b> publishes a {@code FailureClassified} event to
 * the events address for observability.
 *
 * <h2>Why this matters</h2>
 * A real LLM would analyse the lookup data and categorise the failure:
 * <em>"The counterparty LEI is missing and they're rated BBB+ — this is
 * a reference data issue, severity HIGH because the trade value exceeds
 * $1M."</em> This tool models that classification step.
 *
 * <h2>Expected args</h2>
 * <pre>
 * {
 *   "tradeId":  "T-300",
 *   "category": "ReferenceData",
 *   "severity": "HIGH",
 *   "reason":   "Counterparty LEI missing, jurisdiction US, trade value > 1M"
 * }
 * </pre>
 *
 * <h2>Return value</h2>
 * <pre>
 * {
 *   "status":     "classified",
 *   "tradeId":    "T-300",
 *   "category":   "ReferenceData",
 *   "severity":   "HIGH",
 *   "confidence": 0.92,
 *   "reasoning":  "LEI missing for US-jurisdiction counterparty on high-value trade"
 * }
 * </pre>
 */
public class ClassifyTool implements Tool {

  private static final Logger LOG = Logger.getLogger(ClassifyTool.class.getName());

  private final Vertx vertx;
  private final String eventsAddress;

  public ClassifyTool(Vertx vertx, String eventsAddress) {
    this.vertx = vertx;
    this.eventsAddress = eventsAddress;
  }

  @Override
  public String name() {
    return "case.classify";
  }

  @Override
  public String instructions() {
    return """
        ### case.classify
        Use this AFTER data.lookup to categorise the failure. Always include a clear `reason`.
        Categories:
        - `ReferenceData`   — missing or invalid LEI, ISIN, counterparty data
        - `Settlement`      — amount mismatch, unmatched settlement, failed CSD instruction
        - `Duplicate`       — probable duplicate detected via relatedTrades matchScore > 0.95
        - `Compliance`      — sanctions, AML, or regulatory hold
        - `Regulatory`      — deadline breach, reporting obligation missed
        - `CreditRisk`      — counterparty credit event or rating downgrade
        Severity rules:
        - LOW      — minor data gap, no financial exposure, auto-remediable
        - MEDIUM   — data mismatch, manual correction needed, exposure < $500k
        - HIGH     — counterparty or settlement issue, exposure > $500k or deadline risk
        - CRITICAL — sanctions hit, credit event, cascading failure, or regulatory breach
        """;
  }

  @Override
  public String description() {
    return "Classifies a trade failure by category and severity based on the gathered data.";
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
                .put("description", "Failure category, e.g. ReferenceData, Settlement, Compliance"))
            .put("severity", new JsonObject()
                .put("type", "string")
                .put("enum", new JsonArray().add("LOW").add("MEDIUM").add("HIGH").add("CRITICAL"))
                .put("description", "Severity assessment"))
            .put("reason", new JsonObject()
                .put("type", "string")
                .put("description", "Explanation for the classification")))
        .put("required", new JsonArray().add("tradeId").add("category").add("severity"));
  }

  @Override
  public Future<JsonObject> invoke(JsonObject args, AgentContext ctx) {
    String tradeId = args.getString("tradeId", ctx.caseId());
    String category = args.getString("category", "Unknown");
    String severity = args.getString("severity", "MEDIUM");
    String reason = args.getString("reason", "");

    LOG.info("Classifying failure: tradeId=" + tradeId + " category=" + category
        + " severity=" + severity + " caseId=" + ctx.caseId());

    // Simulate a confidence score based on how much info was provided
    double confidence = reason.isEmpty() ? 0.65 : 0.92;

    String reasoning = reason.isEmpty()
        ? "Classification based on failure pattern"
        : reason;

    JsonObject result = new JsonObject()
        .put("status", "classified")
        .put("tradeId", tradeId)
        .put("category", category)
        .put("severity", severity)
        .put("confidence", confidence)
        .put("reasoning", reasoning);

    // Publish a FailureClassified event
    JsonObject event = new JsonObject()
        .put("type", "FailureClassified")
        .put("tradeId", tradeId)
        .put("category", category)
        .put("severity", severity)
        .put("confidence", confidence)
        .put("correlationId", ctx.correlationId())
        .put("caseId", ctx.caseId());
    vertx.eventBus().publish(eventsAddress, event);

    LOG.info("Failure classified: " + category + "/" + severity + " (confidence=" + confidence + ")");
    return Future.succeededFuture(result);
  }
}
