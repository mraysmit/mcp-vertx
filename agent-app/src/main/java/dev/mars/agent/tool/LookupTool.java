package dev.mars.agent.tool;

import dev.mars.mcp.tool.AgentContext;
import dev.mars.mcp.tool.Tool;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.Map;
import java.util.logging.Logger;

/**
 * Agent tool that simulates looking up reference / enrichment data for a
 * trade failure — the kind of external-system query an LLM would instruct
 * as a first step before deciding what action to take.
 *
 * <p><b>Tool name:</b> {@code data.lookup}<br>
 * <b>Side effects:</b> none (read-only simulation).
 *
 * <h2>Why this matters</h2>
 * A real LLM doesn't just jump to an action. It gathers context first:
 * <em>"Let me look up the counterparty… ok, that LEI is expired — I should
 * raise a ReferenceData ticket."</em> This tool models the information-
 * gathering step that makes agentic reasoning valuable.
 *
 * <h2>Expected args</h2>
 * <pre>
 * {
 *   "tradeId":  "T-300",
 *   "fields":   ["counterparty", "security", "settlement"]   // optional
 * }
 * </pre>
 *
 * <h2>Return value</h2>
 * <pre>
 * {
 *   "status":   "found",
 *   "tradeId":  "T-300",
 *   "data": {
 *     "counterparty": { "name": "Acme Corp", "lei": "MISSING", "jurisdiction": "US" },
 *     "security":     { "isin": "US0378331005", "name": "Apple Inc", "currency": "USD" },
 *     "settlement":   { "csd": "DTCC", "status": "UNMATCHED", "valueDate": "2026-03-05" }
 *   }
 * }
 * </pre>
 */
public class LookupTool implements Tool {

  private static final Logger LOG = Logger.getLogger(LookupTool.class.getName());

  // ── Base reference data (used for all scenarios) ──────────────────
  private static final JsonObject DEFAULT_DATA = new JsonObject()
      .put("counterparty", new JsonObject()
          .put("name", "Acme Corp")
          .put("lei", "MISSING")
          .put("jurisdiction", "US")
          .put("rating", "BBB+"))
      .put("security", new JsonObject()
          .put("isin", "US0378331005")
          .put("name", "Apple Inc")
          .put("currency", "USD")
          .put("exchange", "NASDAQ"))
      .put("settlement", new JsonObject()
          .put("csd", "DTCC")
          .put("status", "UNMATCHED")
          .put("valueDate", "2026-03-05")
          .put("expectedAmount", 1_250_000));

  /**
   * Returns scenario-specific reference data based on the trade ID range.
   * This simulates what a real system would return from different data sources.
   */
  private static JsonObject dataForTrade(String tradeId) {
    JsonObject base = DEFAULT_DATA.copy();
    // deep-copy nested objects so mutations don't leak
    for (String key : DEFAULT_DATA.fieldNames()) {
      if (DEFAULT_DATA.getValue(key) instanceof JsonObject nested) {
        base.put(key, nested.copy());
      }
    }

    int num = parseTradeNum(tradeId);

    if (num >= 500 && num < 600) {
      // ── Settlement amount mismatch scenario ─────────────────────
      base.getJsonObject("settlement")
          .put("status", "AMOUNT_MISMATCH")
          .put("expectedAmount", 1_250_000)
          .put("counterpartyAmount", 1_500_000)
          .put("discrepancy", 250_000)
          .put("discrepancyPct", "20%");
      base.put("fx", new JsonObject()
          .put("pair", "EUR/USD")
          .put("ourRate", 1.0842)
          .put("marketRate", 1.0875)
          .put("rateMovement", "+0.30%")
          .put("rateDate", "2026-03-03")
          .put("note", "Rate moved 0.30% since trade booking — "
              + "counterparty may have applied T+1 rate"));

    } else if (num >= 600 && num < 700) {
      // ── Duplicate trade scenario ────────────────────────────────
      base.getJsonObject("settlement")
          .put("status", "PENDING_REVIEW")
          .put("bookedAt", "2026-03-03T14:32:01Z")
          .put("quantity", 10_000);
      base.put("relatedTrades", new JsonArray().add(new JsonObject()
          .put("tradeId", "T-" + (num - 1))
          .put("counterparty", "Acme Corp")
          .put("security", "AAPL")
          .put("quantity", 10_000)
          .put("direction", "BUY")
          .put("bookedAt", "2026-03-03T14:31:58Z")
          .put("timeDelta", "3 seconds earlier")
          .put("status", "SETTLED")
          .put("matchScore", 0.99)));

    } else if (num >= 700 && num < 800) {
      // ── Regulatory deadline scenario ────────────────────────────
      base.getJsonObject("settlement")
          .put("valueDate", "2026-03-03")   // today
          .put("regulatoryRule", "SEC Rule 15c6-1 (T+1)")
          .put("cutoffTime", "16:30 ET")
          .put("hoursRemaining", 3.5)
          .put("status", "UNMATCHED")
          .put("failReportRequired", true);
      base.getJsonObject("counterparty")
          .put("lei", "529900HNOAA1KXQJUH35")
          .put("jurisdiction", "US")
          .put("t1Mandated", true);

    } else if (num >= 800 && num < 900) {
      // ── Sanctions / AML screening scenario ──────────────────────
      base.getJsonObject("counterparty")
          .put("name", "Meridian Trading FZE")
          .put("lei", "549300EXAMPLE99999")
          .put("jurisdiction", "UAE")
          .put("rating", "BB")
          .put("registeredAddress", "Dubai Multi Commodities Centre, Dubai, UAE")
          .put("ultimateBeneficialOwner", "Meridian Holdings Ltd, Limassol, Cyprus");
      base.getJsonObject("security")
          .put("isin", "XS2530301234")
          .put("name", "Meridian 5Y CDS")
          .put("currency", "USD")
          .put("notional", 5_000_000);
      base.getJsonObject("settlement")
          .put("status", "SCREENING_HOLD")
          .put("expectedAmount", 5_000_000)
          .put("valueDate", "2026-03-04");
      base.put("screening", new JsonObject()
          .put("status", "FLAGGED")
          .put("matchType", "FUZZY_NAME")
          .put("matchScore", 0.78)
          .put("flaggedEntity", "Meridian General Trading LLC")
          .put("watchlist", "OFAC SDN List")
          .put("watchlistEntryId", "SDN-29847")
          .put("flaggedJurisdiction", "Iran")
          .put("flagReason", "Name similarity 78% — entity 'Meridian General Trading LLC' "
              + "listed under OFAC SDN for Iranian petroleum sector activity")
          .put("additionalContext", new JsonObject()
              .put("ourEntity", "Meridian Trading FZE (UAE, DMCC)")
              .put("listedEntity", "Meridian General Trading LLC (Iran, petroleum)")
              .put("nameOverlap", "Meridian + Trading")
              .put("jurisdictionMatch", false)
              .put("sectorMatch", false)
              .put("corporateStructureDifference", "FZE (UAE free zone) vs LLC (Iran)")
              .put("historicalFlags", 0)
              .put("yearsAsClient", 7)));

    } else if (num >= 900 && num < 1000) {
      // ── Multi-leg trade cascade scenario ────────────────────────
      base.getJsonObject("counterparty")
          .put("name", "Global Bank AG")
          .put("lei", "529900GLOBALBANK001")
          .put("jurisdiction", "DE")
          .put("rating", "A+");
      base.getJsonObject("security")
          .put("isin", "SWAP-IRS-5Y-" + tradeId)
          .put("name", "5Y USD Interest Rate Swap")
          .put("currency", "USD")
          .put("notional", 50_000_000);
      base.getJsonObject("settlement")
          .put("status", "FAILED")
          .put("failReason", "SSI mismatch on pay leg")
          .put("valueDate", "2026-03-03")
          .put("expectedAmount", 2_150_000);
      base.put("tradeStructure", new JsonObject()
          .put("type", "Interest Rate Swap")
          .put("tenor", "5Y")
          .put("notional", 50_000_000)
          .put("payLeg", new JsonObject()
              .put("tradeId", tradeId)
              .put("direction", "PAY")
              .put("rate", "SOFR + 45bps")
              .put("frequency", "Quarterly")
              .put("status", "FAILED")
              .put("failReason", "SSI mismatch — our SWIFT BIC GBAGDEFF vs expected GBAGDEFFXXX"))
          .put("receiveLeg", new JsonObject()
              .put("tradeId", "T-" + (num + 1))
              .put("direction", "RECEIVE")
              .put("rate", "3.75% Fixed")
              .put("frequency", "Semi-Annual")
              .put("status", "PENDING")
              .put("note", "Blocked — cannot settle receive leg while pay leg is failed"))
          .put("hedgeTrades", new JsonArray()
              .add(new JsonObject()
                  .put("tradeId", "T-" + (num + 10))
                  .put("type", "FX Forward")
                  .put("notional", 50_000_000)
                  .put("status", "SETTLED")
                  .put("note", "FX hedge will be mismatched if swap legs don't settle today"))
              .add(new JsonObject()
                  .put("tradeId", "T-" + (num + 11))
                  .put("type", "Treasury Bond Future")
                  .put("notional", 25_000_000)
                  .put("status", "OPEN")
                  .put("note", "Duration hedge — losing hedge effectiveness with each day of delay")))
          .put("totalExposure", new JsonObject()
              .put("markToMarket", 2_750_000)
              .put("potentialLoss", 850_000)
              .put("dv01", 42_500)
              .put("note", "Each 1bp rate move = $42,500 unhedged exposure")));

    } else if (num >= 1000 && num < 1100) {
      // ── Counterparty credit event scenario ──────────────────────
      base.getJsonObject("counterparty")
          .put("name", "Sterling & Hart Capital")
          .put("lei", "529900STERLINGHART1")
          .put("jurisdiction", "UK")
          .put("rating", "CCC+")
          .put("previousRating", "BBB-")
          .put("downgradeDate", "2026-03-02")
          .put("downgradedBy", "S&P Global")
          .put("creditWatch", "Negative")
          .put("defaultProbability", "23.5%")
          .put("cdsSpread", 850);
      base.getJsonObject("settlement")
          .put("status", "CREDIT_HOLD")
          .put("valueDate", "2026-03-04");
      base.put("positions", new JsonArray()
          .add(new JsonObject()
              .put("tradeId", tradeId)
              .put("type", "Corporate Bond")
              .put("notional", 10_000_000)
              .put("markToMarket", 7_250_000)
              .put("haircut", "27.5%"))
          .add(new JsonObject()
              .put("tradeId", "T-" + (num + 1))
              .put("type", "Interest Rate Swap")
              .put("notional", 25_000_000)
              .put("markToMarket", -1_200_000)
              .put("note", "We OWE counterparty on this position"))
          .add(new JsonObject()
              .put("tradeId", "T-" + (num + 2))
              .put("type", "FX Forward")
              .put("notional", 15_000_000)
              .put("markToMarket", 450_000)
              .put("maturity", "2026-06-15"))
          .add(new JsonObject()
              .put("tradeId", "T-" + (num + 3))
              .put("type", "Equity Total Return Swap")
              .put("notional", 8_000_000)
              .put("markToMarket", 2_100_000)
              .put("underlier", "FTSE 100")));
      base.put("netting", new JsonObject()
          .put("isdaMasterAgreement", true)
          .put("csa", true)
          .put("collateralPosted", 3_500_000)
          .put("collateralType", "US Treasury Bills")
          .put("minimumTransferAmount", 500_000)
          .put("thresholdAmount", 1_000_000)
          .put("grossExposure", 19_800_000)
          .put("netExposure", 8_600_000)
          .put("netAfterCollateral", 5_100_000)
          .put("note", "Net exposure after collateral = $5.1M. ISDA close-out netting "
              + "reduces gross $19.8M to net $8.6M. CSA collateral covers $3.5M."));
    }

    return base;
  }

  private static int parseTradeNum(String tradeId) {
    try {
      return Integer.parseInt(tradeId.replaceAll("[^0-9]", ""));
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  @Override
  public String name() {
    return "data.lookup";
  }

  @Override
  public String instructions() {
    return """
        ### data.lookup
        Use this as the FIRST step in every investigation. Retrieve reference and enrichment
        data for the trade before taking any action.
        - Request all relevant fields: counterparty, security, settlement, fx, relatedTrades.
        - Key signals to look for in the result:
          - `counterparty.lei = "MISSING"` → ReferenceData failure, likely HIGH severity.
          - `settlement.status = "AMOUNT_MISMATCH"` → check `fx` for rate discrepancy.
          - `settlement.status = "PENDING_REVIEW"` + `relatedTrades` present → probable duplicate.
          - `counterparty.sanctions` present → CRITICAL, must publish ComplianceHold.
          - `counterparty.creditEvent` present → CRITICAL, must publish CreditReview.
          - `settlement.regulatoryDeadline` in the past → HIGH, publish RegulatoryBreach.
        - Do not skip this step even if the failure reason seems obvious.
        """;
  }

  @Override
  public String description() {
    return "Looks up reference and enrichment data for a trade from external systems "
        + "(counterparty, security, settlement details).";
  }

  @Override
  public JsonObject schema() {
    return new JsonObject()
        .put("type", "object")
        .put("properties", new JsonObject()
            .put("tradeId", new JsonObject()
                .put("type", "string")
                .put("description", "The trade identifier to look up"))
            .put("fields", new JsonObject()
                .put("type", "array")
                .put("items", new JsonObject().put("type", "string"))
                .put("description", "Specific data fields to retrieve (default: all)")))
        .put("required", new JsonArray().add("tradeId"));
  }

  @Override
  public Future<JsonObject> invoke(JsonObject args, AgentContext ctx) {
    String tradeId = args.getString("tradeId", ctx.caseId());
    LOG.info("Looking up data for tradeId=" + tradeId + " caseId=" + ctx.caseId());

    // Load scenario-appropriate data based on trade ID range
    JsonObject fullData = dataForTrade(tradeId);

    // Simulate field filtering
    JsonArray fields = args.getJsonArray("fields");
    JsonObject data;
    if (fields != null && !fields.isEmpty()) {
      data = new JsonObject();
      for (int i = 0; i < fields.size(); i++) {
        String field = fields.getString(i);
        if (fullData.containsKey(field)) {
          Object val = fullData.getValue(field);
          data.put(field, val instanceof JsonObject jo ? jo.copy()
              : val instanceof JsonArray ja ? ja.copy() : val);
        }
      }
    } else {
      data = fullData;
    }

    JsonObject result = new JsonObject()
        .put("status", "found")
        .put("tradeId", tradeId)
        .put("data", data);

    LOG.info("Lookup completed for tradeId=" + tradeId + ": " + data.fieldNames().size() + " sections returned");
    return Future.succeededFuture(result);
  }
}
