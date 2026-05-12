package dev.mars.mcp.tool;

import dev.mars.mcp.tool.AgentContext;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

/**
 * A named capability that the agent can invoke during its reasoning loop.
 *
 * <p>Each tool has a unique {@link #name()} (e.g. {@code "events.publish"},
 * {@code "case.raiseTicket"}) and an {@link #invoke} method that performs
 * the side-effecting action and returns a result describing what happened.
 *
 * <p>Tools are registered in a {@link ToolRegistry} and only tools present
 * in the allow-list can be called by the agent — this is the primary
 * security boundary between the LLM's decisions and real-world actions.
 *
 * <h2>Self-describing schema</h2>
 * Each tool exposes its parameter schema via {@link #schema()}, following
 * the <a href="https://json-schema.org/">JSON Schema</a> format used by
 * MCP (Model Context Protocol) tools. This enables:
 * <ul>
 *   <li>LLMs to generate correctly-shaped arguments.</li>
 *   <li>Runtime validation of arguments before invocation.</li>
 *   <li>Programmatic discovery (e.g. {@code tools/list} in MCP).</li>
 * </ul>
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@code args} — tool-specific arguments supplied by the LLM command.</li>
 *   <li>{@code ctx} — the current {@link AgentContext} (correlation ID,
 *       case ID, accumulated state), useful for enriching outbound events.</li>
 *   <li>Return value — a {@code JsonObject} describing the outcome;
 *       the agent runner appends it to the memory store.</li>
 * </ul>
 *
 * @see ToolRegistry
 * @see dev.mars.mcp.tool.AgentRunnerVerticle
 */
public interface Tool {

  /** The unique name of this tool as referenced by LLM commands. */
  String name();

  /**
   * A human-readable description of what this tool does.
   *
   * <p>Used by LLMs to decide when to invoke the tool, and by
   * MCP-compatible discovery endpoints ({@code tools/list}).
   *
   * @return a short description (one or two sentences)
   */
  default String description() {
    return "";
  }

  /**
   * Domain-specific instructions for the LLM on when and how to use this tool.
   *
   * <p>These are assembled into the system prompt at runtime so that each tool
   * carries its own guidance — no domain knowledge lives in the LLM client.
   *
   * @return instructions text, or empty string if none
   */
  default String instructions() {
    return "";
  }

  /**
   * JSON Schema describing the parameters this tool accepts.
   *
   * <p>The returned object follows
   * <a href="https://json-schema.org/draft/2020-12/json-schema-core">JSON Schema 2020-12</a>
   * and is compatible with the MCP {@code inputSchema} field.
   *
   * <p>Example:
   * <pre>{@code
   * {
   *   "type": "object",
   *   "properties": {
   *     "tradeId": { "type": "string", "description": "The trade identifier" }
   *   },
   *   "required": ["tradeId"]
   * }
   * }</pre>
   *
   * <p>The default implementation returns an empty object schema
   * ({@code {"type":"object"}}), meaning "accepts any properties".
   *
   * @return the parameter schema as a JsonObject
   */
  default JsonObject schema() {
    return new JsonObject().put("type", "object");
  }

  /**
   * Execute the tool's action.
   *
   * @param args tool-specific arguments from the LLM command
   * @param ctx  the current agent execution context
   * @return a Future with a JSON result describing the outcome
   */
  Future<JsonObject> invoke(JsonObject args, AgentContext ctx);
}
