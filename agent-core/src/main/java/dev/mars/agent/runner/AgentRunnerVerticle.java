package dev.mars.agent.runner;

import dev.mars.agent.llm.LlmClient;
import dev.mars.agent.memory.MemoryStore;
import dev.mars.mcp.tool.AgentContext;
import dev.mars.mcp.tool.Tool;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * LLM-based agent verticle that listens on an injected event bus address
 * and performs an iterative decide → execute → record loop until the LLM
 * signals completion or the safety step-limit is reached.
 *
 * <h2>Key design point: the runner does NOT choose tools</h2>
 * This class is a generic execution engine. It has <b>no domain logic</b>
 * and no knowledge of which tool should handle a given event. The decision
 * of <em>what to do</em> is made entirely by the injected
 * {@link LlmClient}. The runner's only responsibilities are:
 * <ul>
 *   <li>Ask the {@link LlmClient} what to do next
 *       ({@link LlmClient#decideNext}).</li>
 *   <li>Validate that the requested tool exists in the <b>allow-listed</b>
 *       tool map (security boundary).</li>
 *   <li>Invoke the selected tool with the arguments specified by the
 *       LLM.</li>
 *   <li>Record each step in the {@link MemoryStore} for audit/context.</li>
 *   <li>Loop until the LLM says {@code stop: true} or the safety
 *       step-limit is reached.</li>
 * </ul>
 *
 * <h2>Lifecycle</h2>
 * <ol>
 *   <li>Receives a failure event that no deterministic handler could match.</li>
 *   <li>Extracts the case identifier using the configurable {@code caseIdField}
 *       (e.g. {@code "tradeId"}, {@code "settlementId"}).</li>
 *   <li>Loads the case's prior state from the {@link MemoryStore}.</li>
 *   <li>Calls {@link LlmClient#decideNext} — the LLM returns a structured
 *       command naming a tool and its arguments.</li>
 *   <li>Looks up the tool by name in the allow-listed tool map and invokes
 *       it.</li>
 *   <li>Appends the step to the memory store for audit and context.</li>
 *   <li>If {@code stop == false}, loops back to step 4 (up to
 *       {@code agent.max.steps}).</li>
 * </ol>
 *
 * <h2>Configuration (Vert.x config)</h2>
 * <ul>
 *   <li>{@code agent.max.steps} — maximum number of iterative steps before
 *       a safety stop is triggered (default {@code 5}).</li>
 * </ul>
 *
 * <h2>Safety</h2>
 * The configurable step limit prevents runaway loops when the LLM never
 * sets {@code stop: true}. Only tools present in the injected allow-list
 * can be invoked — anything else is rejected with a clear error.
 *
 * @see LlmClient
 * @see Tool
 * @see MemoryStore
 * @see AgentContext
 */
public class AgentRunnerVerticle extends AbstractVerticle {

  private static final Logger LOG = Logger.getLogger(AgentRunnerVerticle.class.getName());
  private static final int DEFAULT_MAX_STEPS = 5;

  private final String listenAddress;
  private final LlmClient llm;
  private final Map<String, Tool> tools;
  private final MemoryStore memory;
  private final String caseIdField;

  private int maxSteps;

  /**
   * Creates a new agent runner verticle.
   *
   * @param listenAddress the event bus address to consume agent requests from
   * @param llm           the LLM client used to decide each step
   * @param tools         allow-listed tool map (name → tool)
   * @param memory        the memory store for case state
   * @param caseIdField   the JSON field name used to extract the case
   *                      identifier from incoming events (e.g. {@code "tradeId"})
   */
  public AgentRunnerVerticle(String listenAddress, LlmClient llm,
                             Map<String, Tool> tools,
                             MemoryStore memory, String caseIdField) {
    this.listenAddress = listenAddress;
    this.llm = llm;
    this.tools = tools;
    this.memory = memory;
    this.caseIdField = caseIdField;
  }

  @Override
  public void start(Promise<Void> startPromise) {
    maxSteps = config().getInteger("agent.max.steps", DEFAULT_MAX_STEPS);

    LOG.info("AgentRunner starting: address=" + listenAddress
        + " maxSteps=" + maxSteps + " tools=" + tools.keySet());

    vertx.eventBus().consumer(listenAddress, msg -> {
      JsonObject event = (JsonObject) msg.body();
      String caseId = event.getString(caseIdField);
      String corrId = event.getString("correlationId", UUID.randomUUID().toString());

      LOG.info("Agent invoked for case=" + caseId + " correlationId=" + corrId);

      memory.load(caseId)
        .compose(state -> runLoop(event, new AgentContext(corrId, caseId, state), 0, new JsonArray()))
        .onSuccess(msg::reply)
        .onFailure(err -> {
          LOG.log(Level.SEVERE, "Agent failed for case=" + caseId, err);
          msg.fail(500, err.getMessage());
        });
    });

    startPromise.complete();
  }

  private Future<JsonObject> runLoop(JsonObject event, AgentContext ctx, int step, JsonArray trail) {
    if (step >= maxSteps) {
      LOG.warning("Step limit reached for case=" + ctx.caseId());
      return Future.succeededFuture(new JsonObject()
        .put("status", "error")
        .put("path", "agent")
        .put("reason", "Step limit reached (safety stop)")
        .put("trail", trail)
        .put(caseIdField, ctx.caseId()));
    }

    LOG.fine("Agent step " + step + " for case=" + ctx.caseId());

    // Keep the state snapshot current so the LLM can vary its behaviour
    // across iterations (e.g. step 0 → lookup, step 1 → classify, step 2 → act).
    ctx.state().put("step", step);

    // Step 1: Ask the LLM what to do — the LLM decides which tool to call
    return llm.decideNext(event, ctx.state())
      // Step 2: Execute the tool the LLM selected (validated against allow-list)
      .compose(cmd -> {
        LOG.info("LLM decided: intent=" + cmd.getString("intent")
            + " tool=" + cmd.getString("tool")
            + " stop=" + cmd.getBoolean("stop", true)
            + " for case=" + ctx.caseId());
        return executeCommand(cmd, ctx);
      })
      // Step 3: Record the step in memory for audit and future LLM context
      .compose(outcome -> {
        JsonObject entry = new JsonObject()
            .put("step", step)
            .put("command", outcome.getJsonObject("command"))
            .put("toolResult", outcome.getJsonObject("toolResult"))
            .put("at", System.currentTimeMillis());
        // Update the in-flight state so the next iteration's LLM call can
        // see the full history of all tools called and their results.
        ctx.state().put("trail", trail);
        ctx.state().put("last", entry);
        trail.add(entry);
        return memory.append(ctx.caseId(), entry).map(v -> outcome);
      })
      // Step 4: Loop if the LLM said stop=false, otherwise return the result
      .compose(outcome -> {
        if (!outcome.getBoolean("stop", true)) {
          LOG.info("Agent continuing to step " + (step + 1) + " for case=" + ctx.caseId());
          return runLoop(event, ctx, step + 1, trail);
        }
        LOG.info("Agent completed for case=" + ctx.caseId() + " after " + (step + 1) + " step(s)");
        return Future.succeededFuture(new JsonObject()
          .put("status", "ok")
          .put("path", "agent")
          .put("result", outcome.getJsonObject("toolResult"))
          .put("trail", trail)
          .put(caseIdField, ctx.caseId()));
      });
  }

  /**
   * Validate and execute the tool command returned by the LLM.
   *
   * <p>The LLM's response names a tool and provides arguments. This method
   * enforces the allow-list: only tools present in the injected {@code tools}
   * map can be invoked. Unknown tool names are rejected immediately.
   */
  private Future<JsonObject> executeCommand(JsonObject cmd, AgentContext ctx) {
    String intent = cmd.getString("intent", "");
    String toolName = cmd.getString("tool", "");
    JsonObject args = cmd.getJsonObject("args", new JsonObject());

    if (!"CALL_TOOL".equals(intent)) {
      LOG.warning("Unsupported intent='" + intent + "' for case=" + ctx.caseId());
      return Future.failedFuture("Unsupported intent: " + intent);
    }

    Tool tool = tools.get(toolName);
    if (tool == null) {
      LOG.warning("Tool not allowlisted: " + toolName
          + " (available: " + tools.keySet() + ") for case=" + ctx.caseId());
      return Future.failedFuture("Tool not allowlisted: " + toolName);
    }

    LOG.info("Invoking tool=" + toolName + " for case=" + ctx.caseId() + " args=" + args.encode());
    return tool.invoke(args, ctx).map(result -> {
      LOG.info("Tool " + toolName + " completed for case=" + ctx.caseId()
          + " result=" + result.encode());
      return new JsonObject()
        .put("stop", cmd.getBoolean("stop", true))
        .put("command", cmd)
        .put("toolResult", result);
    });
  }
}
