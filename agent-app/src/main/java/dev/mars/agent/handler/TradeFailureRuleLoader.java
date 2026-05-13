package dev.mars.agent.handler;
import dev.mars.mcp.tool.Tool;

import dev.mars.agent.llm.StubLlmClient;
import dev.mars.agent.llm.StubRule;
import dev.mars.agent.llm.StubRuleLoader;
import dev.mars.agent.llm.StubRuleSet;
import io.vertx.core.json.JsonObject;

import java.util.List;
import java.util.logging.Logger;

/**
 * Rule loader for the <b>trade-failure</b> use case.
 *
 * <p>Assembles multi-step stub rules that simulate how a real LLM would
 * reason through a trade failure — gathering data, classifying the issue,
 * and then taking the appropriate action.
 *
 * <h2>LEI keyword rule (3 steps)</h2>
 * <ol>
 *   <li><b>Lookup</b> — gather counterparty + settlement data.</li>
 *   <li><b>Classify</b> — ReferenceData / HIGH.</li>
 *   <li><b>Raise ticket</b> — for the Reference Data team.</li>
 * </ol>
 *
 * <h2>Settlement Amount Mismatch rule (4 steps)</h2>
 * <ol>
 *   <li><b>Lookup</b> — settlement, counterparty + FX data.</li>
 *   <li><b>Classify</b> — Settlement / HIGH with FX analysis.</li>
 *   <li><b>Raise ticket</b> — root-cause hypothesis (FX timing).</li>
 *   <li><b>Notify</b> — PagerDuty to Reconciliation + Risk.</li>
 * </ol>
 *
 * <h2>Duplicate Trade rule (4 steps)</h2>
 * <ol>
 *   <li><b>Lookup</b> — full trade data + related trades.</li>
 *   <li><b>Classify</b> — Operations / CRITICAL with cross-reference.</li>
 *   <li><b>Raise ticket</b> — P1 with duplicate evidence.</li>
 *   <li><b>Notify</b> — PagerDuty to Trade Operations.</li>
 * </ol>
 *
 * <h2>Regulatory Deadline rule (4 steps)</h2>
 * <ol>
 *   <li><b>Lookup</b> — settlement timeline + counterparty.</li>
 *   <li><b>Classify</b> — Compliance / CRITICAL with SEC analysis.</li>
 *   <li><b>Raise ticket</b> — with full regulatory context.</li>
 *   <li><b>Notify</b> — PagerDuty to Compliance.</li>
 * </ol>
 *
 * <h2>Sanctions / AML Screening rule (5 steps) — LLM showcase</h2>
 * <ol>
 *   <li><b>Lookup</b> — counterparty + screening match data.</li>
 *   <li><b>Classify</b> — Sanctions / HIGH with multi-factor false-positive
 *       analysis (jurisdiction, sector, entity structure, client history).</li>
 *   <li><b>Raise ticket</b> — with regulatory citation (31 CFR § 501.604)
 *       and structured evidence.</li>
 *   <li><b>Publish event</b> — SanctionsScreeningReview for audit trail.</li>
 *   <li><b>Notify</b> — calibrated channel selection (email vs PagerDuty)
 *       based on false-positive confidence.</li>
 * </ol>
 *
 * <h2>Multi-Leg Cascade rule (5 steps) — LLM showcase</h2>
 * <ol>
 *   <li><b>Lookup</b> — full trade structure (legs + hedges).</li>
 *   <li><b>Classify</b> — Settlement / CRITICAL with cascade impact
 *       analysis (DV01, hedge degradation, aggregate exposure).</li>
 *   <li><b>Publish event</b> — CascadeFailureDetected for downstream systems.</li>
 *   <li><b>Raise ticket</b> — with resolution sequence and cross-domain
 *       reasoning (trivial root cause vs. massive financial impact).</li>
 *   <li><b>Notify</b> — role-tailored messaging for Ops, Trading, Risk.</li>
 * </ol>
 *
 * <h2>Counterparty Credit Event rule (5 steps) — LLM showcase</h2>
 * <ol>
 *   <li><b>Lookup</b> — credit data, positions, netting, collateral.</li>
 *   <li><b>Classify</b> — CreditRisk / CRITICAL with ISDA netting analysis,
 *       directional exposure, and "DO NOT unwind" warnings.</li>
 *   <li><b>Raise ticket</b> — prioritised action plan with portfolio-level
 *       reasoning.</li>
 *   <li><b>Publish event</b> — CounterpartyCreditEvent for risk systems.</li>
 *   <li><b>Notify</b> — cross-functional alerting (Credit Risk, Legal,
 *       Collateral Management).</li>
 * </ol>
 *
 * <h2>Fallback (3 steps)</h2>
 * <ol>
 *   <li><b>Lookup</b> — gather all trade context.</li>
 *   <li><b>Classify</b> — Operations / MEDIUM.</li>
 *   <li><b>Notify</b> — Symphony to Operations team.</li>
 * </ol>
 *
 * <p>Each rule uses {@code stop: false} to continue the agent loop
 * until the final step sets {@code stop: true}. Rules inspect
 * {@code state.getInteger("step")} to determine which step of the
 * reasoning chain to execute.
 *
 * @see StubRuleLoader
 * @see StubRuleSet
 */
public class TradeFailureRuleLoader implements StubRuleLoader {

  private static final Logger LOG = Logger.getLogger(TradeFailureRuleLoader.class.getName());

  @Override
  public StubRuleSet load() {

    // ── LEI keyword rule — 3-step chain ──────────────────────────────
    StubRule leiRule = new StubRule() {
      @Override
      public JsonObject tryMatch(JsonObject event) {
        // single-arg version not used when state is available
        return tryMatch(event, new JsonObject().put("step", 0));
      }

      @Override
      public JsonObject tryMatch(JsonObject event, JsonObject state) {
        String reason = event.getString("reason", "").toLowerCase();
        if (!reason.contains("lei")) return null;

        int step = state != null ? state.getInteger("step", 0) : 0;
        String tradeId = event.getString("tradeId", "unknown");

        return switch (step) {
          // Step 0: Gather data — "Let me look up the counterparty details"
          case 0 -> new JsonObject()
              .put("intent", "CALL_TOOL")
              .put("tool", "data.lookup")
              .put("args", new JsonObject()
                  .put("tradeId", tradeId)
                  .put("fields", new io.vertx.core.json.JsonArray()
                      .add("counterparty").add("settlement")))
              .put("reasoning", "I need to look up the counterparty data to understand the LEI issue")
              .put("stop", false);

          // Step 1: Classify — "The LEI is missing, this is a reference data issue"
          case 1 -> new JsonObject()
              .put("intent", "CALL_TOOL")
              .put("tool", "case.classify")
              .put("args", new JsonObject()
                  .put("tradeId", tradeId)
                  .put("category", "ReferenceData")
                  .put("severity", "HIGH")
                  .put("reason", "Counterparty LEI missing — jurisdiction US, "
                      + "rated BBB+, high-value trade requires valid LEI"))
              .put("reasoning", "Lookup shows LEI is MISSING for a US-jurisdiction "
                  + "counterparty — this is a HIGH severity reference data issue")
              .put("stop", false);

          // Step 2: Act — "I'll raise a ticket for the Reference Data team"
          default -> new JsonObject()
              .put("intent", "CALL_TOOL")
              .put("tool", "case.raiseTicket")
              .put("args", new JsonObject()
                  .put("tradeId", tradeId)
                  .put("category", "ReferenceData")
                  .put("summary", "Counterparty LEI missing — HIGH severity")
                  .put("detail", "Failure reason: " + event.getString("reason")
                      + ". Lookup shows counterparty Acme Corp (US, BBB+) "
                      + "has no LEI on file. Trade is UNMATCHED at DTCC. "
                      + "Classified as ReferenceData/HIGH with 0.92 confidence."))
              .put("reasoning", "Classification confirmed HIGH severity — raising "
                  + "a ReferenceData ticket with full context from lookup")
              .put("stop", true);
        };
      }
    };

    // ── Settlement Amount Mismatch — 4-step chain ─────────────────────
    StubRule mismatchRule = new StubRule() {
      @Override
      public JsonObject tryMatch(JsonObject event) {
        return tryMatch(event, new JsonObject().put("step", 0));
      }

      @Override
      public JsonObject tryMatch(JsonObject event, JsonObject state) {
        String reason = event.getString("reason", "").toLowerCase();
        if (!reason.contains("amount") && !reason.contains("mismatch")
            && !reason.contains("settlement mismatch")) return null;

        int step = state != null ? state.getInteger("step", 0) : 0;
        String tradeId = event.getString("tradeId", "unknown");

        return switch (step) {
          // Step 0: Gather settlement + counterparty + FX data
          case 0 -> new JsonObject()
              .put("intent", "CALL_TOOL")
              .put("tool", "data.lookup")
              .put("args", new JsonObject()
                  .put("tradeId", tradeId)
                  .put("fields", new io.vertx.core.json.JsonArray()
                      .add("settlement").add("counterparty").add("fx")))
              .put("reasoning", "Settlement amount mismatch reported — I need to pull "
                  + "the full settlement record including both our expected amount and "
                  + "the counterparty's submitted amount, plus any FX rate data that "
                  + "could explain a conversion discrepancy.")
              .put("stop", false);

          // Step 1: Classify with FX analysis
          case 1 -> new JsonObject()
              .put("intent", "CALL_TOOL")
              .put("tool", "case.classify")
              .put("args", new JsonObject()
                  .put("tradeId", tradeId)
                  .put("category", "Settlement")
                  .put("severity", "HIGH")
                  .put("reason", "Amount discrepancy of $250,000 (20%). "
                      + "Our expected: $1,250,000 vs counterparty submitted: $1,500,000. "
                      + "FX analysis: EUR/USD moved +0.30% since booking — counterparty "
                      + "may have applied T+1 rate (1.0875) vs our booked rate (1.0842). "
                      + "Correlation suggests FX timing issue, but absolute amount "
                      + "exceeds $10K tolerance."))
              .put("reasoning", "Lookup reveals our expected amount is $1,250,000 but the "
                  + "counterparty submitted $1,500,000 — a $250,000 discrepancy (20%). "
                  + "I notice the EUR/USD rate moved +0.30% since booking and the "
                  + "counterparty is EU-based. This correlation strongly suggests an FX "
                  + "conversion timing issue rather than a genuine trade break. However, "
                  + "the absolute amount ($250K) far exceeds the $10K tolerance threshold, "
                  + "so this must be classified as Settlement / HIGH regardless of root cause.")
              .put("stop", false);

          // Step 2: Raise ticket with root-cause hypothesis
          case 2 -> new JsonObject()
              .put("intent", "CALL_TOOL")
              .put("tool", "case.raiseTicket")
              .put("args", new JsonObject()
                  .put("tradeId", tradeId)
                  .put("category", "Settlement")
                  .put("summary", "Amount mismatch $250K (20%) — likely FX timing issue")
                  .put("detail", "Expected: $1,250,000 | Counterparty: $1,500,000 | "
                      + "Discrepancy: $250,000 (20%).\n\n"
                      + "Root-cause hypothesis: Counterparty (Acme Corp, EU jurisdiction) "
                      + "appears to have applied the T+1 EUR/USD rate (1.0875) vs our "
                      + "booked rate (1.0842). Rate moved +0.30% between booking and "
                      + "settlement instruction.\n\n"
                      + "Action required: Reconciliation desk to verify which FX rate "
                      + "was contractually agreed and whether re-booking at the correct "
                      + "rate resolves the break."))
              .put("reasoning", "The magnitude of this discrepancy ($250K) and its likely "
                  + "root cause (FX rate timing mismatch) require the Reconciliation desk "
                  + "to verify which rate was contractually agreed. I'm creating a HIGH "
                  + "ticket with the FX hypothesis documented so the team can investigate "
                  + "efficiently rather than starting from scratch.")
              .put("stop", false);

          // Step 3: Notify both Reconciliation and Risk
          default -> new JsonObject()
              .put("intent", "CALL_TOOL")
              .put("tool", "comms.notify")
              .put("args", new JsonObject()
                  .put("tradeId", tradeId)
                  .put("channel", "pagerduty")
                  .put("team", "Reconciliation + Risk")
                  .put("subject", "HIGH: $250K settlement mismatch on " + tradeId
                      + " — FX timing issue suspected")
                  .put("body", "Trade " + tradeId + " has a $250,000 (20%) settlement "
                      + "amount mismatch.\n\n"
                      + "Likely cause: FX conversion timing (EUR/USD +0.30% since booking).\n"
                      + "Value date approaching — immediate attention required.\n\n"
                      + "Ticket raised for Reconciliation. Risk team copied due to "
                      + "material exposure."))
              .put("reasoning", "Given the material amount ($250K) and the approaching value "
                  + "date, I'm alerting both Reconciliation (to fix the break) and Risk "
                  + "(to assess exposure) via PagerDuty. Standard Symphony notification isn't "
                  + "appropriate here — waiting for the next business day would increase "
                  + "the risk of a failed settlement.")
              .put("stop", true);
        };
      }
    };

    // ── Duplicate Trade — 4-step chain ──────────────────────────────
    StubRule duplicateRule = new StubRule() {
      @Override
      public JsonObject tryMatch(JsonObject event) {
        return tryMatch(event, new JsonObject().put("step", 0));
      }

      @Override
      public JsonObject tryMatch(JsonObject event, JsonObject state) {
        String reason = event.getString("reason", "").toLowerCase();
        if (!reason.contains("duplicate")) return null;

        int step = state != null ? state.getInteger("step", 0) : 0;
        String tradeId = event.getString("tradeId", "unknown");

        return switch (step) {
          // Step 0: Pull full trade data + related trades
          case 0 -> new JsonObject()
              .put("intent", "CALL_TOOL")
              .put("tool", "data.lookup")
              .put("args", new JsonObject().put("tradeId", tradeId))
              .put("reasoning", "Duplicate trade flagged — I need to examine the full trade "
                  + "details and check for similar recent bookings by the same counterparty. "
                  + "The lookup should reveal any related trades booked in a close time window.")
              .put("stop", false);

          // Step 1: Classify as CRITICAL with cross-reference reasoning
          case 1 -> new JsonObject()
              .put("intent", "CALL_TOOL")
              .put("tool", "case.classify")
              .put("args", new JsonObject()
                  .put("tradeId", tradeId)
                  .put("category", "Operations")
                  .put("severity", "CRITICAL")
                  .put("reason", "High-confidence duplicate detected (match score 0.99). "
                      + "Trade " + tradeId + " booked at 14:32:01 is near-identical to "
                      + "a trade booked 3 seconds earlier — same counterparty (Acme Corp), "
                      + "same security (AAPL), same quantity (10,000 shares), same direction "
                      + "(BUY). Pattern consistent with retry-after-timeout double execution. "
                      + "Unintended market exposure: ~$1.25M."))
              .put("reasoning", "Analysis confirms high duplicate probability: Trade " + tradeId
                  + " for Acme Corp — 10,000 shares AAPL at NASDAQ — was booked at "
                  + "14:32:01. I found a prior trade with IDENTICAL parameters booked at "
                  + "14:31:58, just 3 seconds earlier, with a 0.99 match score. The execution "
                  + "venue, quantity, counterparty, and direction all match exactly. This "
                  + "pattern is consistent with a retry-after-timeout causing double execution. "
                  + "Classifying as Operations / CRITICAL because duplicate execution means "
                  + "unintended market exposure of approximately $1.25M.")
              .put("stop", false);

          // Step 2: Raise urgent P1 ticket
          case 2 -> new JsonObject()
              .put("intent", "CALL_TOOL")
              .put("tool", "case.raiseTicket")
              .put("args", new JsonObject()
                  .put("tradeId", tradeId)
                  .put("category", "Operations")
                  .put("summary", "CRITICAL: Probable duplicate execution — $1.25M unintended exposure")
                  .put("detail", "Two near-identical trades detected:\n\n"
                      + "  Trade " + tradeId + " booked 14:32:01 → PENDING_REVIEW\n"
                      + "  Related trade booked 14:31:58 → SETTLED (match score 0.99)\n\n"
                      + "Both: Acme Corp, 10,000 shares AAPL BUY @ NASDAQ.\n"
                      + "Time delta: 3 seconds — consistent with retry-after-timeout.\n\n"
                      + "URGENT: Verify with execution venue whether both fills are genuine. "
                      + "If confirmed duplicate, cancel " + tradeId + " immediately to "
                      + "eliminate ~$1.25M unintended exposure."))
              .put("reasoning", "CRITICAL situation requires an urgent P1 ticket for Trade "
                  + "Operations. I'm documenting the key evidence — two identical fills "
                  + "3 seconds apart, same counterparty, same security, same quantity — so "
                  + "the team can immediately verify with the execution venue whether both "
                  + "fills are genuine or if " + tradeId + " should be cancelled.")
              .put("stop", false);

          // Step 3: PagerDuty alert (Symphony too slow for CRITICAL)
          default -> new JsonObject()
              .put("intent", "CALL_TOOL")
              .put("tool", "comms.notify")
              .put("args", new JsonObject()
                  .put("tradeId", tradeId)
                  .put("channel", "pagerduty")
                  .put("team", "Trade Operations")
                  .put("subject", "CRITICAL: Duplicate execution — " + tradeId
                      + " / ~$1.25M exposure")
                  .put("body", "DUPLICATE EXECUTION ALERT\n\n"
                      + "Trade " + tradeId + " appears to be a duplicate of a trade "
                      + "booked 3 seconds earlier (match score 0.99).\n\n"
                      + "Unintended exposure: ~$1.25M on AAPL.\n"
                      + "Pattern: retry-after-timeout — verify with venue immediately.\n\n"
                      + "Ticket created. This is a P1 — requires immediate response."))
              .put("reasoning", "Duplicate execution is a CRITICAL risk scenario with ~$1.25M "
                  + "of unintended AAPL exposure. Standard Symphony notification is NOT "
                  + "sufficient — every minute of delay increases risk if the market moves "
                  + "against the duplicate position. Escalating to PagerDuty to ensure the "
                  + "Trade Operations on-call team is alerted immediately for manual "
                  + "intervention.")
              .put("stop", true);
        };
      }
    };

    // ── Regulatory Deadline at Risk — 4-step chain ──────────────────
    StubRule regulatoryRule = new StubRule() {
      @Override
      public JsonObject tryMatch(JsonObject event) {
        return tryMatch(event, new JsonObject().put("step", 0));
      }

      @Override
      public JsonObject tryMatch(JsonObject event, JsonObject state) {
        String reason = event.getString("reason", "").toLowerCase();
        if (!reason.contains("regulatory") && !reason.contains("deadline")
            && !reason.contains("compliance") && !reason.contains("t+1")) return null;

        int step = state != null ? state.getInteger("step", 0) : 0;
        String tradeId = event.getString("tradeId", "unknown");

        return switch (step) {
          // Step 0: Check settlement timeline
          case 0 -> new JsonObject()
              .put("intent", "CALL_TOOL")
              .put("tool", "data.lookup")
              .put("args", new JsonObject()
                  .put("tradeId", tradeId)
                  .put("fields", new io.vertx.core.json.JsonArray()
                      .add("settlement").add("counterparty")))
              .put("reasoning", "Compliance-flagged failure — I need to check the settlement "
                  + "timeline and regulatory context immediately. If this trade has a same-day "
                  + "value date under T+1 rules, the deadline may be imminent.")
              .put("stop", false);

          // Step 1: Classify as CRITICAL/Compliance with regulatory analysis
          case 1 -> new JsonObject()
              .put("intent", "CALL_TOOL")
              .put("tool", "case.classify")
              .put("args", new JsonObject()
                  .put("tradeId", tradeId)
                  .put("category", "Compliance")
                  .put("severity", "CRITICAL")
                  .put("reason", "SEC Rule 15c6-1 (T+1) deadline at risk. "
                      + "Value date is TODAY (2026-03-03), trade status UNMATCHED at DTCC. "
                      + "Estimated 3.5 hours until DTCC cutoff at 16:30 ET. "
                      + "Counterparty is US-jurisdiction, T+1 mandated. "
                      + "Failure to settle will trigger mandatory SEC fail report."))
              .put("reasoning", "CRITICAL finding: Settlement value date is 2026-03-03 "
                  + "(TODAY) and the trade is still in UNMATCHED status at DTCC. Under SEC "
                  + "Rule 15c6-1, this trade MUST settle by end of day. Current time analysis: "
                  + "approximately 3.5 hours remain until DTCC's final settlement cutoff at "
                  + "16:30 ET. The counterparty (Acme Corp, US jurisdiction, LEI confirmed) is "
                  + "subject to the same T+1 requirements. Failure to settle will trigger a "
                  + "mandatory fail report to the SEC — this is not just an operational issue, "
                  + "it is a potential regulatory breach. Classifying as Compliance / CRITICAL.")
              .put("stop", false);

          // Step 2: Raise ticket with full regulatory context
          case 2 -> new JsonObject()
              .put("intent", "CALL_TOOL")
              .put("tool", "case.raiseTicket")
              .put("args", new JsonObject()
                  .put("tradeId", tradeId)
                  .put("category", "Compliance")
                  .put("summary", "CRITICAL: SEC T+1 deadline breach imminent — " + tradeId)
                  .put("detail", "Trade " + tradeId + " is at risk of breaching SEC Rule "
                      + "15c6-1 (T+1 settlement mandate).\n\n"
                      + "Value date: 2026-03-03 (TODAY)\n"
                      + "Current status: UNMATCHED at DTCC\n"
                      + "DTCC cutoff: 16:30 ET (~3.5 hours remaining)\n"
                      + "Counterparty: Acme Corp (US, T+1 mandated)\n\n"
                      + "If not resolved before cutoff, a mandatory fail report must be "
                      + "filed with the SEC. The Compliance team should assess whether "
                      + "pre-emptive notification to the regulator is advisable.\n\n"
                      + "IMMEDIATE ACTION: Contact DTCC and counterparty to resolve "
                      + "matching discrepancy before 16:30 ET."))
              .put("reasoning", "Creating a CRITICAL compliance ticket with the full regulatory "
                  + "context. The SEC fail-report deadline creates a hard constraint — this "
                  + "isn't a soft SLA, it's a legal obligation. I'm documenting the specific "
                  + "timeline (3.5h remaining), the applicable rule (15c6-1), and the "
                  + "consequence (mandatory SEC fail report) so the team understands the "
                  + "urgency without needing to research it themselves.")
              .put("stop", false);

          // Step 3: PagerDuty to Compliance
          default -> new JsonObject()
              .put("intent", "CALL_TOOL")
              .put("tool", "comms.notify")
              .put("args", new JsonObject()
                  .put("tradeId", tradeId)
                  .put("channel", "pagerduty")
                  .put("team", "Compliance")
                  .put("subject", "CRITICAL: SEC T+1 breach imminent — " + tradeId
                      + " UNMATCHED, cutoff 16:30 ET")
                  .put("body", "REGULATORY DEADLINE ALERT\n\n"
                      + "Trade " + tradeId + " will breach SEC Rule 15c6-1 (T+1) if not "
                      + "settled by 16:30 ET today.\n\n"
                      + "Status: UNMATCHED at DTCC\n"
                      + "Time remaining: ~3.5 hours\n"
                      + "Consequence: Mandatory SEC fail report\n\n"
                      + "Compliance ticket created. Immediate action required to contact "
                      + "DTCC and counterparty for matching resolution."))
              .put("reasoning", "Regulatory deadline breach is imminent — notifying Compliance "
                  + "via PagerDuty, not Symphony. The SEC T+1 mandate means this cannot wait "
                  + "for the next standup or business day. I'm including specific actionable "
                  + "context: trade ID, current status (UNMATCHED), regulatory rule (15c6-1), "
                  + "and estimated time remaining (~3.5h) before the DTCC cutoff so the "
                  + "on-call Compliance officer can act immediately.")
              .put("stop", true);
        };
      }
    };

    // ── Fallback rule — 3-step chain for any unrecognised reason ──────
    StubRule fallback = new StubRule() {
      @Override
      public JsonObject tryMatch(JsonObject event) {
        return tryMatch(event, new JsonObject().put("step", 0));
      }

      @Override
      public JsonObject tryMatch(JsonObject event, JsonObject state) {
        int step = state != null ? state.getInteger("step", 0) : 0;
        String tradeId = event.getString("tradeId", "unknown");
        String reason = event.getString("reason", "Unknown failure");

        return switch (step) {
          // Step 0: Gather data — "Let me look up all available information"
          case 0 -> new JsonObject()
              .put("intent", "CALL_TOOL")
              .put("tool", "data.lookup")
              .put("args", new JsonObject().put("tradeId", tradeId))
              .put("reasoning", "Unrecognised failure — gathering all available "
                  + "trade data before deciding on an action")
              .put("stop", false);

          // Step 1: Classify — "Based on what I found, this looks like..."
          case 1 -> new JsonObject()
              .put("intent", "CALL_TOOL")
              .put("tool", "case.classify")
              .put("args", new JsonObject()
                  .put("tradeId", tradeId)
                  .put("category", "Operations")
                  .put("severity", "MEDIUM")
                  .put("reason", "Unrecognised failure pattern: " + reason
                      + " — trade data shows UNMATCHED status, "
                      + "requires operational investigation"))
              .put("reasoning", "Trade data retrieved — failure doesn't match "
                  + "known patterns, classifying as Operations/MEDIUM for investigation")
              .put("stop", false);

          // Step 2: Notify — "I'll alert the Operations team"
          default -> new JsonObject()
              .put("intent", "CALL_TOOL")
              .put("tool", "comms.notify")
              .put("args", new JsonObject()
                  .put("tradeId", tradeId)
                  .put("channel", "Symphony")
                  .put("team", "Operations")
                  .put("subject", "MEDIUM: Investigation needed for trade " + tradeId)
                  .put("body", "Trade " + tradeId + " failed with reason: " + reason
                      + ".\n\nClassified as Operations/MEDIUM.\n"
                      + "Settlement status: UNMATCHED at DTCC.\n"
                      + "Please investigate and resolve."))
              .put("reasoning", "Classified as Operations/MEDIUM — notifying the "
                  + "Operations team via Symphony with full context")
              .put("stop", true);
        };
      }
    };

    // ── Sanctions / AML Screening — 5-step adaptive chain ────────────
    //
    // WHY THIS NEEDS AN LLM:
    // • Interprets fuzzy name matches — is "Meridian Trading FZE" the same
    //   entity as "Meridian General Trading LLC"? An LLM can reason about
    //   corporate structures, jurisdictions, and naming conventions.
    // • Weighs multiple evidence dimensions (jurisdiction, sector, ownership,
    //   client history) to assess false-positive probability.
    // • Understands sanctions regulations across OFAC, EU, UK regimes and
    //   knows which require "blocking" vs "reporting" obligations.
    // • Crafts nuanced communications — Legal needs different detail than
    //   Compliance, and the tone must reflect confidence level.
    //
    StubRule sanctionsRule = new StubRule() {
      @Override
      public JsonObject tryMatch(JsonObject event) {
        return tryMatch(event, new JsonObject().put("step", 0));
      }

      @Override
      public JsonObject tryMatch(JsonObject event, JsonObject state) {
        String reason = event.getString("reason", "").toLowerCase();
        if (!reason.contains("sanction") && !reason.contains("screening")
            && !reason.contains("ofac") && !reason.contains("watchlist")
            && !reason.contains("aml")) return null;

        int step = state != null ? state.getInteger("step", 0) : 0;
        String tradeId = event.getString("tradeId", "unknown");

        return switch (step) {
          // Step 0: Gather screening + counterparty data
          case 0 -> new JsonObject()
              .put("intent", "CALL_TOOL")
              .put("tool", "data.lookup")
              .put("args", new JsonObject()
                  .put("tradeId", tradeId)
                  .put("fields", new io.vertx.core.json.JsonArray()
                      .add("counterparty").add("screening").add("settlement").add("security")))
              .put("reasoning", "A sanctions screening flag is a potentially serious "
                  + "compliance event. Before I can assess the risk, I need to see the "
                  + "screening match details — specifically the match score, which watchlist "
                  + "was triggered, and the counterparty's full profile. I also need "
                  + "settlement and security data to understand the trade's materiality.")
              .put("stop", false);

          // Step 1: Deep analysis — LLM reasons about false-positive probability
          case 1 -> new JsonObject()
              .put("intent", "CALL_TOOL")
              .put("tool", "case.classify")
              .put("args", new JsonObject()
                  .put("tradeId", tradeId)
                  .put("category", "Sanctions")
                  .put("severity", "HIGH")
                  .put("reason", "OFAC SDN fuzzy match (78%) on counterparty name — "
                      + "LIKELY FALSE POSITIVE based on multi-factor analysis:\n\n"
                      + "1. Name similarity: 'Meridian Trading FZE' vs 'Meridian General "
                      + "Trading LLC' — 78% overlap, but common commercial name pattern.\n"
                      + "2. Jurisdiction: Our entity is UAE (DMCC free zone), flagged entity "
                      + "is Iran — DIFFERENT jurisdictions.\n"
                      + "3. Sector: Our entity trades commodities derivatives, flagged entity "
                      + "is in Iranian petroleum — DIFFERENT sectors.\n"
                      + "4. Corporate structure: FZE (UAE free zone entity) vs LLC (Iranian "
                      + "domestic) — structurally distinct.\n"
                      + "5. Client history: 7 years as client, ZERO prior screening flags.\n"
                      + "6. Beneficial ownership: UBO is Cyprus-based Meridian Holdings Ltd, "
                      + "no connection to Iranian interests found.\n\n"
                      + "Assessment: High confidence (>90%) this is a false positive. However, "
                      + "per OFAC guidance, a 78% name match on the SDN list requires formal "
                      + "review and documentation regardless of false-positive assessment. "
                      + "Recommending 'investigate and release' workflow, NOT a blocking action."))
              .put("reasoning", "This is where LLM reasoning is critical. I need to weigh "
                  + "SIX different evidence dimensions to distinguish a true sanctions hit "
                  + "from a false positive:\n\n"
                  + "Looking at the screening match: 78% name similarity is above the typical "
                  + "70% threshold that triggers manual review, but well below the 95%+ that "
                  + "would indicate a strong match. The key differentiators are:\n"
                  + "• The jurisdictions are completely different (UAE vs Iran)\n"
                  + "• The business sectors don't overlap\n"
                  + "• The corporate structures are different entity types\n"
                  + "• 7 years of clean client history with zero prior flags\n\n"
                  + "A keyword-based system would just see 'OFAC SDN match' and escalate at "
                  + "maximum severity. An LLM can recognize this pattern as a likely false "
                  + "positive while still respecting the regulatory requirement for formal "
                  + "review. I'm classifying as HIGH (not CRITICAL) to ensure review happens "
                  + "without triggering an unnecessary blocking action that would damage the "
                  + "client relationship.")
              .put("stop", false);

          // Step 2: Raise ticket with regulatory-aware documentation
          case 2 -> new JsonObject()
              .put("intent", "CALL_TOOL")
              .put("tool", "case.raiseTicket")
              .put("args", new JsonObject()
                  .put("tradeId", tradeId)
                  .put("category", "Sanctions")
                  .put("summary", "OFAC SDN fuzzy match (78%) — probable false positive, "
                      + "formal review required")
                  .put("detail", "SANCTIONS SCREENING REVIEW REQUIRED\n\n"
                      + "Trade: " + tradeId + " | $5M CDS | Meridian Trading FZE (UAE)\n\n"
                      + "Screening hit: OFAC SDN List entry SDN-29847\n"
                      + "Flagged entity: Meridian General Trading LLC (Iran, petroleum)\n"
                      + "Match type: FUZZY_NAME | Score: 78%\n\n"
                      + "FALSE POSITIVE ASSESSMENT (>90% confidence):\n"
                      + "✗ Jurisdiction mismatch: UAE (DMCC) ≠ Iran\n"
                      + "✗ Sector mismatch: Commodities derivatives ≠ Petroleum\n"
                      + "✗ Entity type mismatch: FZE ≠ LLC\n"
                      + "✗ No prior screening flags in 7 years\n"
                      + "✗ UBO (Cyprus) has no Iranian connections\n\n"
                      + "REGULATORY REQUIREMENT:\n"
                      + "Per 31 CFR § 501.604, a name match ≥70% on the SDN list requires "
                      + "documented review even when assessed as false positive. Trade is "
                      + "currently on SCREENING_HOLD — recommend 'investigate and release' "
                      + "workflow after Compliance sign-off.\n\n"
                      + "RECOMMENDED ACTIONS:\n"
                      + "1. Compliance officer reviews screening file and UBO documentation\n"
                      + "2. If false positive confirmed, release hold and document rationale\n"
                      + "3. File SAR if any suspicious indicators found during review\n"
                      + "4. Update client screening profile to reduce future false positives"))
              .put("reasoning", "I'm creating a comprehensive ticket that gives the Compliance "
                  + "team everything they need to make a decision WITHOUT doing additional "
                  + "research. This includes:\n"
                  + "• The specific regulatory citation (31 CFR § 501.604) so they know the "
                  + "legal basis for the review requirement\n"
                  + "• A structured false-positive assessment with evidence for each factor\n"
                  + "• Concrete recommended actions in priority order\n"
                  + "• The current trade status (SCREENING_HOLD) so they know the trade is safe\n\n"
                  + "A rule-based system would create a generic 'sanctions flag — please review' "
                  + "ticket. The LLM-generated ticket gives the analyst a head start by doing "
                  + "the initial assessment — they just need to verify, not start from scratch.")
              .put("stop", false);

          // Step 3: Publish event for audit trail
          case 3 -> new JsonObject()
              .put("intent", "CALL_TOOL")
              .put("tool", "events.publish")
              .put("args", new JsonObject()
                  .put("type", "SanctionsScreeningReview")
                  .put("tradeId", tradeId)
                  .put("reason", "OFAC SDN fuzzy name match — formal review initiated")
                  .put("matchScore", 0.78)
                  .put("assessment", "PROBABLE_FALSE_POSITIVE")
                  .put("regulatoryBasis", "31 CFR § 501.604"))
              .put("reasoning", "Sanctions events require a complete audit trail. I'm publishing "
                  + "a structured event that downstream systems (audit log, regulatory reporting, "
                  + "risk dashboard) can consume. The event includes the assessment outcome and "
                  + "regulatory basis so the audit trail is self-documenting.")
              .put("stop", false);

          // Step 4: Notify Compliance — calibrated urgency
          default -> new JsonObject()
              .put("intent", "CALL_TOOL")
              .put("tool", "comms.notify")
              .put("args", new JsonObject()
                  .put("tradeId", tradeId)
                  .put("channel", "email")
                  .put("team", "Compliance + Legal")
                  .put("subject", "HIGH: Sanctions screening review — " + tradeId
                      + " | OFAC SDN match 78% | Probable false positive")
                  .put("body", "SANCTIONS SCREENING ALERT\n\n"
                      + "Trade " + tradeId + " (Meridian Trading FZE, $5M CDS) has been "
                      + "flagged by OFAC SDN screening (78% name match).\n\n"
                      + "Initial assessment: PROBABLE FALSE POSITIVE (>90% confidence)\n"
                      + "Key factors: Different jurisdictions (UAE vs Iran), different sectors, "
                      + "7 years clean history.\n\n"
                      + "Trade is on SCREENING_HOLD — no settlement risk.\n"
                      + "Compliance review ticket created with full analysis.\n\n"
                      + "This requires documented review per 31 CFR § 501.604 but does NOT "
                      + "appear to warrant a blocking action or SAR at this time."))
              .put("reasoning", "I'm choosing EMAIL over PagerDuty because this is assessed as "
                  + "a probable false positive with the trade already safely on hold. PagerDuty "
                  + "would be appropriate for a high-confidence true positive where immediate "
                  + "blocking action is needed. Email gives Compliance the right level of "
                  + "urgency — important but not panic-inducing — and provides a written record "
                  + "that supports the regulatory documentation requirement.\n\n"
                  + "A keyword-based system would either always use PagerDuty (over-escalation, "
                  + "alert fatigue) or always use email (under-escalation for true positives). "
                  + "An LLM calibrates the notification channel to the assessed risk level.")
              .put("stop", true);
        };
      }
    };

    // ── Multi-Leg Cascade — 5-step structural reasoning chain ─────────
    //
    // WHY THIS NEEDS AN LLM:
    // • Understands trade structure relationships — a swap has pay/receive
    //   legs, hedges are linked to the underlying, and failure cascades.
    // • Calculates aggregate exposure across linked trades — DV01, MtM,
    //   and hedge effectiveness decay.
    // • Reasons about which legs to prioritize fixing first based on
    //   dependency ordering and time constraints.
    // • Knows that an SSI mismatch (BIC format issue) is a data problem,
    //   not a credit or market problem, and communicates accordingly.
    //
    StubRule cascadeRule = new StubRule() {
      @Override
      public JsonObject tryMatch(JsonObject event) {
        return tryMatch(event, new JsonObject().put("step", 0));
      }

      @Override
      public JsonObject tryMatch(JsonObject event, JsonObject state) {
        String reason = event.getString("reason", "").toLowerCase();
        if (!reason.contains("cascade") && !reason.contains("linked")
            && !reason.contains("swap leg") && !reason.contains("multi-leg")) return null;

        int step = state != null ? state.getInteger("step", 0) : 0;
        String tradeId = event.getString("tradeId", "unknown");

        return switch (step) {
          // Step 0: Look up the failed trade AND its linked structure
          case 0 -> new JsonObject()
              .put("intent", "CALL_TOOL")
              .put("tool", "data.lookup")
              .put("args", new JsonObject()
                  .put("tradeId", tradeId))
              .put("reasoning", "A cascade or linked trade failure requires understanding "
                  + "the FULL trade structure, not just the failed leg. I need to see:\n"
                  + "• What type of structure this is (swap, strip, basket)\n"
                  + "• All linked legs and their current status\n"
                  + "• Any hedges that depend on this trade settling\n"
                  + "• The aggregate exposure if the failure persists\n\n"
                  + "Without this structural view, I might fix one leg while missing "
                  + "that three other positions are now at risk.")
              .put("stop", false);

          // Step 1: Classify with structural cascade analysis
          case 1 -> new JsonObject()
              .put("intent", "CALL_TOOL")
              .put("tool", "case.classify")
              .put("args", new JsonObject()
                  .put("tradeId", tradeId)
                  .put("category", "Settlement")
                  .put("severity", "CRITICAL")
                  .put("reason", "Cascading failure across 4-trade structure:\n\n"
                      + "ROOT CAUSE: SSI mismatch on pay leg — SWIFT BIC format issue "
                      + "(GBAGDEFF vs GBAGDEFFXXX). This is a data configuration error, "
                      + "NOT a credit or market issue.\n\n"
                      + "CASCADE IMPACT:\n"
                      + "1. Pay leg (T-900): FAILED — SSI mismatch prevents settlement\n"
                      + "2. Receive leg (T-901): BLOCKED — cannot settle while pay leg failed\n"
                      + "3. FX hedge (T-910): SETTLED but now MISMATCHED — swap non-settlement "
                      + "creates FX exposure on the $50M notional\n"
                      + "4. Duration hedge (T-911): LOSING EFFECTIVENESS — each day of delay "
                      + "degrades the interest rate hedge by approximately $42,500/bp\n\n"
                      + "TOTAL EXPOSURE: $2.75M MtM + $850K potential loss\n"
                      + "DV01 (unhedged): $42,500 per 1bp move\n\n"
                      + "PRIORITY: Fix the SSI data (trivial root cause) to resolve the "
                      + "entire cascade — this is a 5-minute fix with outsized impact."))
              .put("reasoning", "This is a textbook example of why LLM reasoning matters. "
                  + "A rule-based system would see 'SSI mismatch' and classify it as a "
                  + "LOW/MEDIUM data quality issue. But by understanding the TRADE STRUCTURE, "
                  + "I can see that this simple data error has cascading consequences:\n\n"
                  + "• It's blocking $50M notional of swap settlement\n"
                  + "• Two hedge trades are now mismatched or degrading\n"
                  + "• Total unhedged exposure is growing at $42,500 per basis point\n\n"
                  + "The severity is CRITICAL not because of the root cause (trivial SSI fix) "
                  + "but because of the IMPACT. The fix is easy but time-sensitive — every "
                  + "hour of delay increases rate exposure. I'm classifying as Settlement/"
                  + "CRITICAL with a clear note that the root cause is simple, to prevent "
                  + "the team from overthinking the fix while the exposure grows.")
              .put("stop", false);

          // Step 2: Publish system event to halt downstream processing
          case 2 -> new JsonObject()
              .put("intent", "CALL_TOOL")
              .put("tool", "events.publish")
              .put("args", new JsonObject()
                  .put("type", "CascadeFailureDetected")
                  .put("tradeId", tradeId)
                  .put("reason", "Multi-leg cascade: SSI mismatch on pay leg blocking "
                      + "4-trade structure settlement")
                  .put("affectedTrades", new io.vertx.core.json.JsonArray()
                      .add(tradeId).add("T-901").add("T-910").add("T-911"))
                  .put("rootCause", "SSI_DATA_MISMATCH")
                  .put("severity", "CRITICAL"))
              .put("reasoning", "Before raising the ticket, I need to publish a system event "
                  + "so that downstream consumers (risk engine, P&L, collateral management) "
                  + "know that these 4 trades are part of a linked failure. This prevents "
                  + "the risk engine from independently flagging T-910 and T-911 as separate "
                  + "issues, which would create duplicate tickets and confusion.\n\n"
                  + "An LLM understands that in a complex trading system, actions have "
                  + "downstream effects and coordinates accordingly. A simple rule would "
                  + "just fix the immediate issue without considering the broader system state.")
              .put("stop", false);

          // Step 3: Raise ticket with fix instructions
          case 3 -> new JsonObject()
              .put("intent", "CALL_TOOL")
              .put("tool", "case.raiseTicket")
              .put("args", new JsonObject()
                  .put("tradeId", tradeId)
                  .put("category", "Settlement")
                  .put("summary", "CRITICAL: SSI fix needed — blocking $50M swap + 2 hedges | "
                      + "DV01 $42.5K unhedged")
                  .put("detail", "CASCADING SETTLEMENT FAILURE\n\n"
                      + "Root cause: SSI mismatch on pay leg\n"
                      + "  Our BIC: GBAGDEFF (8-char) | Required: GBAGDEFFXXX (11-char)\n"
                      + "  Fix: Update SSI in settlement system to use 11-character BIC\n\n"
                      + "AFFECTED TRADES (4):\n"
                      + "  " + tradeId + " | Pay leg (SOFR+45bp) | FAILED\n"
                      + "  T-901 | Receive leg (3.75% Fixed) | BLOCKED (waiting on pay leg)\n"
                      + "  T-910 | FX Forward $50M | SETTLED but mismatched\n"
                      + "  T-911 | Treasury Bond Future $25M | Hedge degrading\n\n"
                      + "EXPOSURE:\n"
                      + "  Mark-to-Market: $2.75M\n"
                      + "  Potential loss: $850K\n"
                      + "  DV01 (unhedged): $42,500/bp\n\n"
                      + "PRIORITY: P1 — Simple SSI data fix resolves entire cascade.\n"
                      + "Estimated fix time: 5 minutes once Operations updates the BIC.\n"
                      + "Every hour of delay = additional unhedged rate risk.\n\n"
                      + "RESOLUTION SEQUENCE:\n"
                      + "1. Update SSI BIC from GBAGDEFF to GBAGDEFFXXX\n"
                      + "2. Resubmit pay leg T-900 for settlement\n"
                      + "3. Receive leg T-901 will auto-settle once pay leg clears\n"
                      + "4. Verify FX hedge T-910 alignment\n"
                      + "5. Confirm duration hedge T-911 effectiveness"))
              .put("reasoning", "I'm providing the resolution SEQUENCE — not just 'fix the SSI' "
                  + "but the specific order in which the cascade should be unwound. I'm also "
                  + "highlighting the contrast between root cause complexity (trivial — update "
                  + "a BIC) and impact severity (CRITICAL — $2.75M MtM, growing DV01 exposure). "
                  + "This framing helps the operations team prioritize: they should drop "
                  + "everything and fix this 5-minute data issue because the opportunity cost "
                  + "of delay is enormous.\n\n"
                  + "An LLM can do this because it understands both the technical fix AND the "
                  + "financial consequence — a capability that requires cross-domain reasoning.")
              .put("stop", false);

          // Step 4: Notify multiple desks with role-appropriate messaging
          default -> new JsonObject()
              .put("intent", "CALL_TOOL")
              .put("tool", "comms.notify")
              .put("args", new JsonObject()
                  .put("tradeId", tradeId)
                  .put("channel", "pagerduty")
                  .put("team", "Settlement Operations + Trading Desk + Risk")
                  .put("subject", "CRITICAL: $50M swap cascade — SSI fix needed — "
                      + "DV01 $42.5K/bp unhedged")
                  .put("body", "CASCADING SETTLEMENT FAILURE — IMMEDIATE ACTION REQUIRED\n\n"
                      + "A simple SSI data error (BIC format) is blocking settlement of a "
                      + "$50M interest rate swap structure, leaving 4 trades and $2.75M MtM "
                      + "at risk.\n\n"
                      + "FOR SETTLEMENT OPS: Update BIC from GBAGDEFF → GBAGDEFFXXX and "
                      + "resubmit " + tradeId + ". Estimated fix time: 5 minutes.\n\n"
                      + "FOR TRADING DESK: Be aware that your duration hedge (T-911) is "
                      + "degrading. If settlement is delayed past 16:00 ET, consider "
                      + "temporary delta hedge.\n\n"
                      + "FOR RISK: Unhedged DV01 of $42,500/bp. Current rate vol suggests "
                      + "~$85K VaR(95%) overnight if not resolved today.\n\n"
                      + "P1 ticket created with full resolution sequence."))
              .put("reasoning", "I'm sending ONE notification to THREE teams but with DIFFERENT "
                  + "actionable information for each:\n"
                  + "• Settlement Ops gets the specific fix instruction\n"
                  + "• Trading Desk gets awareness of hedge degradation + contingency\n"
                  + "• Risk gets quantified exposure metrics (DV01, VaR)\n\n"
                  + "An LLM can tailor the same alert for different audiences because it "
                  + "understands what each team cares about. A rule-based system would send "
                  + "the same generic message to everyone, or require separate notification "
                  + "templates for every possible scenario — neither scales.")
              .put("stop", true);
        };
      }
    };

    // ── Counterparty Credit Event — 5-step cross-domain chain ─────────
    //
    // WHY THIS NEEDS AN LLM:
    // • Interprets credit events (downgrades, defaults, watch status)
    //   and understands their implications under ISDA agreements.
    // • Calculates net exposure from gross positions using netting and
    //   collateral — requires understanding financial math AND legal
    //   agreements simultaneously.
    // • Prioritises positions by risk (not alphabetically or by size)
    //   and identifies which positions benefit vs. harm under close-out.
    // • Distinguishes between "we owe them" and "they owe us" positions
    //   to correctly assess directional risk.
    //
    StubRule creditEventRule = new StubRule() {
      @Override
      public JsonObject tryMatch(JsonObject event) {
        return tryMatch(event, new JsonObject().put("step", 0));
      }

      @Override
      public JsonObject tryMatch(JsonObject event, JsonObject state) {
        String reason = event.getString("reason", "").toLowerCase();
        if (!reason.contains("credit") && !reason.contains("downgrade")
            && !reason.contains("default") && !reason.contains("rating")) return null;

        int step = state != null ? state.getInteger("step", 0) : 0;
        String tradeId = event.getString("tradeId", "unknown");

        return switch (step) {
          // Step 0: Gather credit + position + netting data
          case 0 -> new JsonObject()
              .put("intent", "CALL_TOOL")
              .put("tool", "data.lookup")
              .put("args", new JsonObject()
                  .put("tradeId", tradeId))
              .put("reasoning", "A counterparty credit event triggers a complex chain of "
                  + "analysis. I need the FULL picture before acting:\n"
                  + "• Current and previous credit rating + who downgraded them\n"
                  + "• ALL outstanding positions with this counterparty (not just this trade)\n"
                  + "• Netting agreements + collateral coverage\n"
                  + "• Whether ISDA close-out provisions are triggered\n\n"
                  + "Acting on a single trade without seeing the portfolio-level exposure "
                  + "would be like treating a symptom while ignoring the disease.")
              .put("stop", false);

          // Step 1: Portfolio-level classification with exposure analysis
          case 1 -> new JsonObject()
              .put("intent", "CALL_TOOL")
              .put("tool", "case.classify")
              .put("args", new JsonObject()
                  .put("tradeId", tradeId)
                  .put("category", "CreditRisk")
                  .put("severity", "CRITICAL")
                  .put("reason", "5-notch downgrade (BBB- → CCC+) by S&P on Sterling & Hart "
                      + "Capital. This is a MATERIAL credit event with portfolio-wide implications:\n\n"
                      + "EXPOSURE ANALYSIS:\n"
                      + "Gross exposure across 4 positions: $19.8M\n"
                      + "Net exposure (after ISDA netting): $8.6M\n"
                      + "Net after CSA collateral ($3.5M UST): $5.1M\n\n"
                      + "POSITION BREAKDOWN:\n"
                      + "1. Corporate Bond ($10M, MtM $7.25M) — We hold their debt, 27.5% "
                      + "haircut already applied. Further downgrade = additional write-down.\n"
                      + "2. IRS ($25M, MtM -$1.2M) — WE OWE THEM on this swap. Under ISDA "
                      + "close-out netting, this REDUCES our net exposure. Do NOT pre-pay.\n"
                      + "3. FX Forward ($15M, MtM $450K) — Maturing June. At CCC+ rating, "
                      + "settlement risk is elevated.\n"
                      + "4. Equity TRS ($8M, MtM $2.1M) — FTSE underlier. Margin call "
                      + "likely given CSA threshold breach.\n\n"
                      + "ISDA ANALYSIS:\n"
                      + "The 5-notch downgrade from investment grade to CCC+ likely constitutes "
                      + "a 'Credit Event' under Section 5(b)(v) of the ISDA Master Agreement. "
                      + "CSA threshold of $1M is probably breached given the new rating — this "
                      + "triggers an immediate collateral call.\n\n"
                      + "CRITICAL INSIGHT: Position #2 (IRS, -$1.2M) is an ASSET in a close-out "
                      + "scenario because netting reduces our gross exposure. Do NOT attempt to "
                      + "unwind this position separately — it is more valuable as a netting offset."))
              .put("reasoning", "This classification demonstrates exactly why a financial "
                  + "domain LLM is essential. I'm doing FIVE things simultaneously that no "
                  + "rule-based system could handle:\n\n"
                  + "1. INTERPRETING the credit event: A 5-notch downgrade to CCC+ is "
                  + "qualitatively different from a 1-notch BBB- to BB+. The gap between "
                  + "these is the difference between 'monitor' and 'act immediately'.\n\n"
                  + "2. CALCULATING net exposure: Gross $19.8M → net $8.6M → net after "
                  + "collateral $5.1M. This isn't just subtraction — I'm applying ISDA "
                  + "close-out netting logic to determine the ACTUAL risk.\n\n"
                  + "3. DIRECTIONAL ANALYSIS: Position #2 is NEGATIVE MtM (we owe them) "
                  + "which means it HELPS us under netting. A naive system would see 'we "
                  + "owe $1.2M to a failing counterparty' and try to pre-pay — which would "
                  + "INCREASE our net exposure by $1.2M. The LLM knows not to do this.\n\n"
                  + "4. TRIGGERING ANALYSIS: Understanding that Section 5(b)(v) ISDA credit "
                  + "event provisions and CSA threshold breaches are likely triggered, which "
                  + "changes the legal remedies available.\n\n"
                  + "5. PRIORITISATION: Not all positions are equal. The bond has the most "
                  + "downside, the IRS is actually protective, the FX forward has settlement "
                  + "risk, and the TRS needs a margin call. Each requires different action.")
              .put("stop", false);

          // Step 2: Raise ticket with prioritised action plan
          case 2 -> new JsonObject()
              .put("intent", "CALL_TOOL")
              .put("tool", "case.raiseTicket")
              .put("args", new JsonObject()
                  .put("tradeId", tradeId)
                  .put("category", "CreditRisk")
                  .put("summary", "CRITICAL: Sterling & Hart 5-notch downgrade (BBB- → CCC+) | "
                      + "Net exposure $5.1M after netting + collateral")
                  .put("detail", "COUNTERPARTY CREDIT EVENT — IMMEDIATE ACTION REQUIRED\n\n"
                      + "Sterling & Hart Capital downgraded BBB- → CCC+ by S&P (2026-03-02)\n"
                      + "Credit Watch: Negative | Default probability: 23.5% | CDS: 850bps\n\n"
                      + "NET EXPOSURE WATERFALL:\n"
                      + "  Gross positions (4 trades): $19,800,000\n"
                      + "  After ISDA netting:         $8,600,000\n"
                      + "  After CSA collateral:       $5,100,000\n\n"
                      + "PRIORITISED ACTION PLAN:\n\n"
                      + "1. IMMEDIATE — Issue CSA collateral call\n"
                      + "   CSA threshold ($1M) likely breached at CCC+. Demand additional "
                      + "collateral of ~$4.1M to cover net exposure.\n\n"
                      + "2. URGENT — Assess ISDA close-out rights\n"
                      + "   Determine if Section 5(b)(v) Credit Event has occurred. If yes, "
                      + "we may terminate and close out all positions at once under netting.\n\n"
                      + "3. DO NOT — Pre-pay or unwind the IRS (T-1001)\n"
                      + "   We owe $1.2M on this position. Under netting, this REDUCES our "
                      + "exposure. Pre-paying would INCREASE net exposure by $1.2M.\n\n"
                      + "4. MONITOR — FX Forward (T-1002, June maturity)\n"
                      + "   Settlement risk elevated. Consider novation to a stronger "
                      + "counterparty if situation deteriorates.\n\n"
                      + "5. REVIEW — Corporate Bond (T-1000)\n"
                      + "   Current haircut 27.5%. Model additional write-down scenarios "
                      + "for CCC+ → D transition.\n\n"
                      + "CRITICAL WARNING: Do NOT take actions that reduce netting benefit. "
                      + "The IRS offset saves $1.2M of exposure. Any position-level unwind "
                      + "should be evaluated in portfolio context, not isolation."))
              .put("reasoning", "The ticket includes a PRIORITISED action plan — not just 'do X' "
                  + "but 'do X first, then Y, and specifically do NOT do Z'. The 'DO NOT' "
                  + "instruction for the IRS position is arguably the most valuable insight: "
                  + "it prevents a well-intentioned but counterproductive action that would "
                  + "increase exposure by $1.2M.\n\n"
                  + "This kind of nuanced, cross-position reasoning — where the correct action "
                  + "for one position depends on its relationship to other positions — is "
                  + "precisely what makes LLM-driven analysis superior to per-trade rules.")
              .put("stop", false);

          // Step 3: Publish event for risk systems
          case 3 -> new JsonObject()
              .put("intent", "CALL_TOOL")
              .put("tool", "events.publish")
              .put("args", new JsonObject()
                  .put("type", "CounterpartyCreditEvent")
                  .put("tradeId", tradeId)
                  .put("reason", "S&P downgrade BBB- → CCC+ — Sterling & Hart Capital")
                  .put("counterparty", "Sterling & Hart Capital")
                  .put("netExposure", 5_100_000)
                  .put("collateralCallRequired", true))
              .put("reasoning", "Publishing to the event bus so risk engines, collateral "
                  + "management, and regulatory reporting systems all receive the credit event "
                  + "simultaneously. The net exposure figure allows downstream systems to "
                  + "update capital reserves and regulatory ratios automatically.")
              .put("stop", false);

          // Step 4: Notify with cross-functional awareness
          default -> new JsonObject()
              .put("intent", "CALL_TOOL")
              .put("tool", "comms.notify")
              .put("args", new JsonObject()
                  .put("tradeId", tradeId)
                  .put("channel", "pagerduty")
                  .put("team", "Credit Risk + Legal + Collateral Management")
                  .put("subject", "CRITICAL: Sterling & Hart CCC+ downgrade — "
                      + "$5.1M net exposure — Collateral call + ISDA review required")
                  .put("body", "COUNTERPARTY CREDIT EVENT\n\n"
                      + "Sterling & Hart Capital downgraded BBB- → CCC+ by S&P.\n"
                      + "Default probability: 23.5% | CDS spread: 850bps\n\n"
                      + "FOR CREDIT RISK: Net exposure $5.1M after ISDA netting + CSA "
                      + "collateral. 4 positions affected. See ticket for exposure waterfall.\n\n"
                      + "FOR LEGAL: Assess whether Section 5(b)(v) ISDA Credit Event is "
                      + "triggered. Close-out netting may be advisable if default probability "
                      + "increases.\n\n"
                      + "FOR COLLATERAL MGMT: CSA threshold ($1M) likely breached. Initiate "
                      + "collateral call for ~$4.1M.\n\n"
                      + "⚠ CRITICAL: Do NOT unwind IRS position T-1001 separately — it "
                      + "provides $1.2M netting benefit. Portfolio-level actions only.\n\n"
                      + "Ticket created with full analysis and action plan."))
              .put("reasoning", "Three distinct teams need to act on this, each with different "
                  + "responsibilities:\n"
                  + "• Credit Risk manages the exposure and decides on position limits\n"
                  + "• Legal assesses ISDA termination rights and close-out options\n"
                  + "• Collateral Management executes the margin call\n\n"
                  + "I'm repeating the IRS warning because it's the most dangerous mistake "
                  + "they could make — a trader under pressure might instinctively unwind "
                  + "the position we OWE on, not realizing it's actually protecting us.\n\n"
                  + "The notification goes via PagerDuty because a 5-notch downgrade to CCC+ "
                  + "with 23.5% default probability is genuinely time-critical. Markets are "
                  + "likely already moving on this news, and every hour without a collateral "
                  + "call increases our unsecured exposure.")
              .put("stop", true);
        };
      }
    };

    LOG.info("Loaded trade-failure stub rules: 7 multi-step rules + multi-step fallback");
    return new StubRuleSet(
        List.of(leiRule, mismatchRule, duplicateRule, regulatoryRule,
                sanctionsRule, cascadeRule, creditEventRule),
        fallback);
  }
}
