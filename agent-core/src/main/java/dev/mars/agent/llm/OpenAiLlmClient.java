package dev.mars.agent.llm;

import dev.mars.mcp.tool.Tool;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * {@link LlmClient} implementation that calls a real LLM via the
 * <a href="https://platform.openai.com/docs/api-reference/chat">OpenAI
 * Chat Completions API</a> (also compatible with Azure OpenAI and any
 * provider that exposes the same REST shape).
 *
 * <p>Uses the <b>function-calling / tools</b> feature so the model
 * returns structured JSON that maps directly to the command schema the
 * {@link dev.mars.agent.runner.AgentRunnerVerticle AgentRunnerVerticle}
 * expects.
 *
 * <h2>Configuration</h2>
 * Set in {@code pipeline.yaml}:
 * <pre>
 * llm:
 *   type: "openai"
 *   params:
 *     endpoint: "https://api.openai.com/v1"
 *     apiKey: "${OPENAI_API_KEY}"
 *     model: "gpt-4o"
 * </pre>
 *
 * @see LlmClient
 * @see StubLlmClient
 */
public class OpenAiLlmClient implements LlmClient {

  private static final Logger LOG = Logger.getLogger(OpenAiLlmClient.class.getName());

  private final WebClient webClient;
  private final String endpoint;
  private final String apiKey;
  private final String model;
  private final JsonArray toolsDef;
  private final String systemPrompt;
  /** Maps sanitized API name (dots replaced with underscores) back to real tool name. */
  private final Map<String, String> apiNameToToolName = new HashMap<>();

  /**
   * Creates a new OpenAI LLM client.
   *
   * @param vertx    the Vert.x instance
   * @param endpoint the LLM API base URL
   *                 (e.g. {@code "https://api.openai.com/v1"})
   * @param apiKey   the API key for authentication
   * @param model    the model identifier (e.g. {@code "gpt-4o"})
   * @param tools    the list of agent tools whose schemas will be sent
   *                 to the model for function-calling
   */
  public OpenAiLlmClient(Vertx vertx, String endpoint, String apiKey,
                          String model, Collection<Tool> tools) {
    this.endpoint = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
    this.apiKey = apiKey;
    this.model = model;
    this.webClient = WebClient.create(vertx, new WebClientOptions()
        .setSsl(this.endpoint.startsWith("https"))
        .setTrustAll(false)
        .setConnectTimeout(30_000)
        .setIdleTimeout(120));
    this.toolsDef = buildToolsDef(tools, apiNameToToolName);
    this.systemPrompt = buildSystemPrompt(tools);
    LOG.info("OpenAiLlmClient created: endpoint=" + this.endpoint
        + " model=" + model + " tools=" + toolsDef.size());
  }

  @Override
  public Future<JsonObject> decideNext(JsonObject event, JsonObject state) {
    LOG.info("decideNext: tradeId=" + event.getString("tradeId")
        + " step=" + state.getInteger("step", 0));

    JsonArray messages = buildMessages(event, state);

    JsonObject requestBody = new JsonObject()
        .put("model", model)
        .put("messages", messages)
        .put("tools", toolsDef)
        .put("tool_choice", "auto")
        .put("temperature", 0.1);

    String url = endpoint + "/chat/completions";

    return webClient.postAbs(url)
        .putHeader("Authorization", "Bearer " + apiKey)
        .putHeader("Content-Type", "application/json")
        .sendJsonObject(requestBody)
        .map(response -> {
          int status = response.statusCode();
          if (status < 200 || status >= 300) {
            String body = response.bodyAsString();
            LOG.severe("LLM API error: status=" + status + " body=" + body);
            throw new RuntimeException("LLM API returned " + status + ": " + body);
          }
          return parseResponse(response.bodyAsJsonObject());
        });
  }

  // ── Message construction ──────────────────────────────────────────

  private JsonArray buildMessages(JsonObject event, JsonObject state) {
    JsonArray messages = new JsonArray();

    // System prompt
    messages.add(new JsonObject()
        .put("role", "system")
        .put("content", systemPrompt));

    // User message with the trade failure event
    StringBuilder userMsg = new StringBuilder();
    userMsg.append("A trade failure event has been received. Analyse it and decide what action to take.\n\n");
    userMsg.append("## Trade Failure Event\n```json\n");
    userMsg.append(event.encodePrettily());
    userMsg.append("\n```\n");

    JsonArray priorSteps = state.getJsonArray("trail", new JsonArray());
    if (!priorSteps.isEmpty()) {
      userMsg.append("\n## Investigation so far\n");
      for (int i = 0; i < priorSteps.size(); i++) {
        JsonObject priorEntry = priorSteps.getJsonObject(i);
        userMsg.append("\n### Step ").append(i + 1).append("\n");
        JsonObject prevCommand = priorEntry.getJsonObject("command");
        if (prevCommand != null) {
          userMsg.append("- Tool: `").append(prevCommand.getString("tool", "?")).append("`\n");
          userMsg.append("- Args: ").append(prevCommand.getJsonObject("args", new JsonObject()).encode()).append("\n");
        }
        JsonObject prevResult = priorEntry.getJsonObject("toolResult");
        if (prevResult != null) {
          userMsg.append("- Result:\n```json\n");
          userMsg.append(prevResult.encodePrettily());
          userMsg.append("\n```\n");
        }
      }
    }

    int currentStep = state.getInteger("step", 0);
    userMsg.append("\nThis is step ").append(currentStep + 1).append(" of the investigation.");
    if (priorSteps.isEmpty()) {
      userMsg.append(" Begin by gathering data on the trade failure.");
    } else {
      userMsg.append(" Based on the investigation so far, decide the next action.");
    }

    messages.add(new JsonObject()
        .put("role", "user")
        .put("content", userMsg.toString()));

    return messages;
  }

  // ── Response parsing ──────────────────────────────────────────────

  /**
   * Parse the OpenAI chat completion response into the command schema
   * expected by AgentRunnerVerticle.
   */
  private JsonObject parseResponse(JsonObject responseBody) {
    LOG.fine("LLM response: " + responseBody.encode());

    JsonArray choices = responseBody.getJsonArray("choices");
    if (choices == null || choices.isEmpty()) {
      throw new RuntimeException("LLM returned no choices: " + responseBody.encode());
    }

    JsonObject message = choices.getJsonObject(0).getJsonObject("message");
    JsonArray toolCalls = message.getJsonArray("tool_calls");

    if (toolCalls != null && !toolCalls.isEmpty()) {
      // Model wants to call a function
      JsonObject toolCall = toolCalls.getJsonObject(0);
      JsonObject function = toolCall.getJsonObject("function");
      String toolName = function.getString("name");
      // Reverse-map the sanitized API name back to the real tool name (e.g. events_publish → events.publish)
      toolName = apiNameToToolName.getOrDefault(toolName, toolName);
      String argsStr = function.getString("arguments", "{}");

      JsonObject args;
      try {
        args = new JsonObject(argsStr);
      } catch (Exception e) {
        LOG.warning("Failed to parse function arguments as JSON: " + argsStr);
        args = new JsonObject();
      }

      // Determine if this should be the final step.
      // Ticket-raising and notification tools typically conclude the investigation.
      boolean stop = isTerminalTool(toolName);

      LOG.info("LLM decided: tool=" + toolName + " stop=" + stop
          + " args=" + args.encode());

      return new JsonObject()
          .put("intent", "CALL_TOOL")
          .put("tool", toolName)
          .put("args", args)
          .put("stop", stop);
    }

    // Model returned a text response instead of a function call.
    // Treat as a final "no-action" step — raise a ticket to escalate.
    String content = message.getString("content", "");
    LOG.warning("LLM returned text instead of tool call: " + content);

    return new JsonObject()
        .put("intent", "CALL_TOOL")
        .put("tool", "case.raiseTicket")
        .put("args", new JsonObject()
            .put("tradeId", "unknown")
            .put("category", "Unresolved")
            .put("summary", "LLM could not determine next action")
            .put("detail", content))
        .put("stop", true);
  }

  /**
   * Determines whether calling this tool should be the final step.
   * Lookup and classify tools gather info → continue. Ticket, notify,
   * and publish tools take action → stop.
   */
  private boolean isTerminalTool(String toolName) {
    return switch (toolName) {
      case "data.lookup", "case.classify" -> false;
      case "case.raiseTicket", "comms.notify", "events.publish" -> true;
      default -> true;
    };
  }

  // ── Tool definitions for function-calling ─────────────────────────

  /**
   * Converts the agent's {@link Tool} list into the OpenAI
   * {@code tools} array format for function-calling.
   * Tool names containing dots are sanitized to underscores (OpenAI requires ^[a-zA-Z0-9_-]+$).
   */
  private static JsonArray buildToolsDef(Collection<Tool> tools, Map<String, String> reverseMap) {
    JsonArray arr = new JsonArray();
    for (Tool tool : tools) {
      String apiName = tool.name().replace('.', '_');
      reverseMap.put(apiName, tool.name());
      arr.add(new JsonObject()
          .put("type", "function")
          .put("function", new JsonObject()
              .put("name", apiName)
              .put("description", tool.description())
              .put("parameters", tool.schema())));
    }
    return arr;
  }

  // ── System prompt ─────────────────────────────────────────────────

  private static String buildSystemPrompt(Collection<Tool> tools) {
    StringBuilder sb = new StringBuilder();
    sb.append("""
        You are an expert trade-failure resolution agent at a financial institution.
        Your job is to investigate trade settlement failures and take appropriate corrective \
        actions using the tools available to you.

        Always include the `tradeId` in tool arguments when available.
        Keep reasoning focused on the trade data — do not speculate.

        ## Tool instructions
        """);
    for (Tool tool : tools) {
      String instr = tool.instructions();
      if (instr != null && !instr.isBlank()) {
        sb.append(instr).append("\n");
      }
    }
    return sb.toString();
  }
}
