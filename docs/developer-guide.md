# mcp-vertx developer guide

This guide is for contributors implementing MCP tools, A2A agents, persistent
task stores, transport changes, or runtime integrations. Operators should begin
with the [user guide](user-guide.md).

## Development baseline

- Java 25
- Vert.x 5.1.x and its future-native `VerticleBase` lifecycle
- Maven 3.9 or later
- JUnit 5 with real implementations, protocol fixtures, and lightweight fakes
- No Mockito or substitute mocking framework
- Strict RED, GREEN, refactor development

Read the repository's [coding principles](coding-principles.md) before changing
request handling, concurrency, cancellation, validation, or protocol behavior.

Run the baseline gate before starting work:

```powershell
mvn clean verify 2>&1 | Tee-Object -FilePath logs/baseline-verify.log
```

The `logs/` directory is ignored by Git and survives `mvn clean`.

## Architecture

MCP and A2A share one runtime entry point and cross-cutting conventions, but
remain separate transports and listeners.

```text
Main
├── ServiceLoader<Tool> ──> ToolRegistry ──> McpServerVerticle :3001
└── ServiceLoader<A2aAgent> ───────────────> A2aServerVerticle :3002
                                               └── A2aTaskStore
```

The separation is intentional:

- MCP tools are individually registered capabilities called by MCP clients.
- A2A exposes one agent with its own identity, skills, messages, tasks, and
  lifecycle.
- Protocol models never cross between MCP and A2A merely because both are
  hosted in one process.
- Startup validates providers before accepting ambiguous configuration.

### MCP request flow

```text
HTTP security and limits
  -> body collection
  -> JSON-RPC and metadata validation
  -> method dispatch
  -> tool schema validation
  -> Tool invocation
  -> result validation/serialization
  -> bounded HTTP response
```

### A2A request flow

```text
public Agent Card discovery
or
operational authentication
  -> A2A-Version validation
  -> bounded body/query decoding
  -> A2aAgent invocation
  -> task-store mutation
  -> ProtoJSON response or backpressured SSE
```

The A2A codec uses the official Java SDK records and generated protobuf schema.
Do not introduce parallel local DTOs for normative protocol types.

## Implement an MCP tool

The compatibility SPI is `dev.mars.mcp.tool.Tool`. A minimal asynchronous tool:

```java
package example;

import dev.mars.mcp.tool.Tool;
import dev.mars.mcp.tool.ToolContext;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

public final class EchoTool implements Tool {
  @Override
  public String name() {
    return "text.echo";
  }

  @Override
  public String description() {
    return "Returns the supplied text";
  }

  @Override
  public JsonObject schema() {
    return new JsonObject()
        .put("type", "object")
        .put("properties", new JsonObject()
            .put("text", new JsonObject().put("type", "string")))
        .put("required", new JsonArray().add("text"))
        .put("additionalProperties", false);
  }

  @Override
  public Future<JsonObject> invoke(JsonObject arguments, ToolContext context) {
    return Future.succeededFuture(
        new JsonObject().put("text", arguments.getString("text")));
  }
}
```

Tool names must match the registry's supported ASCII name format and must be
unique. Definitions and schemas are validated during server construction so a
bad provider fails before the listener starts.

### Register a standalone tool

Create this provider resource in the tool JAR:

```text
META-INF/services/dev.mars.mcp.tool.Tool
```

Its content is one implementation class per line:

```text
example.EchoTool
```

The implementation must be public and constructible by `ServiceLoader`.
Multiple tool providers are supported; duplicate tool names are rejected.

### Embed MCP programmatically

```java
ToolRegistry tools = ToolRegistry.of(new EchoTool());
vertx.deployVerticle(new McpServerVerticle(tools));
```

Use a `DeploymentOptions` config when overriding standalone defaults.

### Rich and managed tool results

Override `definition()` to advertise an output schema, title, icons,
annotations, or execution metadata. Override `invokeManaged()` when the tool
needs native content blocks, explicit cancellation, or multi-round-trip input.

```java
@Override
public ToolInvocation invokeManaged(JsonObject arguments, ToolContext context) {
  Future<ToolResult> work = execute(arguments, context)
      .map(value -> new CompleteToolResult(
          List.of(ContentBlock.text("Completed")),
          value,
          false,
          new JsonObject()));
  return new ToolInvocation(work, () -> cancelExternalWork(context.correlationId()));
}
```

Observe `ToolContext.cancellation()` and `remainingTimeMillis()` when calling
external systems. The cancellation callback must be idempotent and return a
non-null `Future<Void>`.

Use `ToolExecutionException` only when its message is deliberately safe for the
remote client. Other failures are logged with a correlation ID and translated
to a generic error.

### Schema rules

- Input and output schemas must be self-contained.
- JSON Schema 2020-12 and draft-07 are supported.
- External `$ref` values are rejected.
- Schema depth, nodes, composition branches, property counts, regex length, and
  serialized size are bounded.
- `x-mcp-header` is accepted only on statically reachable primitive properties.
- Do not disable validation to accommodate an invalid provider schema.

## Implement an A2A agent

Implement `dev.mars.a2a.A2aAgent` with official
`org.a2aproject.sdk.spec` records:

```java
package example;

import dev.mars.a2a.A2aAgent;
import io.vertx.core.Future;
import org.a2aproject.sdk.spec.*;

import java.util.List;

public final class ExampleAgent implements A2aAgent {
  private final AgentCard card = AgentCard.builder()
      .name("Example agent")
      .description("Answers example requests")
      .version("1.0.0")
      .capabilities(AgentCapabilities.builder().streaming(false).build())
      .defaultInputModes(List.of("text/plain"))
      .defaultOutputModes(List.of("text/plain"))
      .skills(List.of(AgentSkill.builder()
          .id("answer")
          .name("Answer")
          .description("Answers a text request")
          .tags(List.of("answer"))
          .build()))
      .supportedInterfaces(List.of(new AgentInterface(
          "HTTP+JSON", "http://127.0.0.1:3002/a2a", null, "1.0")))
      .build();

  @Override
  public AgentCard agentCard() {
    return card;
  }

  @Override
  public Future<EventKind> sendMessage(MessageSendParams request) {
    Message response = Message.builder()
        .role(Message.Role.ROLE_AGENT)
        .messageId(java.util.UUID.randomUUID().toString())
        .parts(new TextPart("Example response"))
        .build();
    return Future.succeededFuture(response);
  }
}
```

Register exactly one provider:

```text
META-INF/services/dev.mars.a2a.A2aAgent
```

The standalone runtime rejects zero or multiple providers when A2A is enabled.
Embedded applications may construct `A2aServerVerticle` directly.

### Immediate messages versus tasks

Return a `Message` when the request can complete immediately. Return a `Task`
when work has a lifecycle, needs later retrieval, produces incremental
artifacts, or may require cancellation.

Every task needs a stable ID, context ID, and status. Do not mutate terminal
tasks. Context IDs correlate related messages and tasks; task IDs identify one
unit of work.

### Implement streaming

Advertise streaming in the Agent Card and override `streamMessage`:

```java
@Override
public Future<Void> streamMessage(
    MessageSendParams request, A2aEventEmitter emitter) {
  Task working = createWorkingTask(request);
  TaskStatusUpdateEvent completed = createCompletedUpdate(working);
  return emitter.emit(working)
      .compose(ignored -> emitter.emit(completed));
}
```

Always compose the future returned by `emit`. It represents task-store
application and HTTP write backpressure. Fire-and-forget emission can reorder
events, hide failures, and exhaust response buffers.

The first event that introduces stateful work must initialize the task before
status or artifact updates refer to it. A terminal status closes task
subscriptions.

### Implement cancellation

Override `cancelTask(Task)` only when the agent can cancel work:

```java
@Override
public Future<Task> cancelTask(Task task) {
  return stopWork(task.id()).map(ignored -> Task.builder(task)
      .status(new TaskStatus(TaskState.TASK_STATE_CANCELED))
      .build());
}
```

The returned task must retain the requested task ID. The transport rejects
cancellation of terminal tasks and persists the returned snapshot.

## Provide durable A2A task storage

`InMemoryA2aTaskStore` is appropriate for tests and single-process ephemeral
deployments. Implement `A2aTaskStore` for persistence or clustering:

```java
public final class DatabaseTaskStore implements A2aTaskStore {
  public void save(Task task) { /* atomic snapshot write */ }
  public Optional<Task> get(String taskId) { /* lookup */ }
  public A2aTaskPage list(ListTasksParams params) { /* stable cursor query */ }
  public Future<Void> apply(StreamingEventKind event) { /* update and publish */ }
  public A2aTaskSubscription subscribe(
      String taskId, A2aEventEmitter emitter) { /* snapshot plus live feed */ }
}
```

Then compose the embedded server explicitly:

```java
vertx.deployVerticle(new A2aServerVerticle(agent, new DatabaseTaskStore()));
```

A production implementation must preserve these invariants:

- terminal snapshots cannot change;
- status/artifact updates match the task context;
- list ordering is newest status timestamp first, then task ID;
- cursors are stable or rejected clearly when stale;
- the subscription snapshot and listener registration do not lose intervening
  updates;
- subscriber failures and backpressure propagate through `Future<Void>`;
- subscription cleanup is idempotent.

`save`, `get`, `list`, and `subscribe` are synchronous snapshot operations and
must not perform blocking database I/O on the event loop. A durable adapter can
serve these operations from a coherently maintained local cache and persist
through the asynchronous `apply` path. If the product requires direct
asynchronous database reads, evolve the store SPI and its transport tests
rather than hiding blocking calls inside the existing methods.

## Vert.x execution rules

Both transports use standard event-loop verticles. Never block their request
paths with `Thread.sleep`, `Future.await`, `join`, blocking database drivers, or
unbounded CPU work.

Move short blocking work deliberately:

```java
return vertx.executeBlocking(() -> blockingCall(), false);
```

Prefer native asynchronous clients. Preserve the Vert.x context when adapting
arbitrary `CompletionStage` instances, and carry deadlines/cancellation into
external calls.

Use `compose` for dependent asynchronous steps, `map` for synchronous value
conversion, and `eventually` for asynchronous cleanup. Terminal observers such
as `onSuccess` are not workflow sequencing primitives.

## Logging requirements

Application code logs through SLF4J; Logback is the packaged backend; Vert.x
uses its SLF4J delegate. Use parameterized logging or lazy `atDebug()` suppliers.

Appropriate INFO events include:

- listener lifecycle and selected safe configuration;
- provider counts and capability names;
- request outcomes and safe rejection categories;
- tool/task state transitions.

DEBUG may add routing, validation, timing, concurrency, and response-type
details. Never log bearer tokens, OAuth tokens, request arguments, A2A message
bodies, input responses, private metadata, or tool-result bodies.

`MCP_LOG_LEVEL` and `A2A_LOG_LEVEL` independently control the application
packages. Access records use `MCP_HTTP_LOG_LEVEL` because both transports use
the shared Vert.x `LoggerHandler` category.

## Security changes

Treat authentication, origin validation, body limits, response limits,
timeouts, concurrency, and terminal-state checks as protocol behavior. Any
change needs HTTP-level tests proving both acceptance and rejection paths.

Maintain these defaults:

- loopback binding without authentication;
- rejection of unauthenticated public binding;
- constant-time fixed-token comparison;
- public A2A discovery but protected A2A operational routes when configured;
- no wildcard MCP browser origins;
- no secrets or bodies in diagnostics.

Do not add a second authentication implementation merely to avoid extending an
existing policy boundary. Keep an Agent Card's advertised security schemes
consistent with the gateway or server policy that actually enforces them.

## Strict TDD workflow

For every behavior change:

1. Add the smallest test expressing an observable outcome.
2. Run it and retain a RED log under `logs/`.
3. Implement only enough production behavior to satisfy the test.
4. Run the focused test and retain a GREEN log.
5. Refactor while the focused test stays green.
6. Run the complete Maven gate.
7. Run protocol and packaging checks when the affected boundary requires them.

Tests should use real Vert.x HTTP servers and clients for transport behavior,
real protocol serialization, and small purpose-built implementations for tool
or agent behavior. Mockito and alternative mocking frameworks are prohibited.

Useful focused commands:

```powershell
mvn clean test '-Dtest=A2aServerVerticleTest'
mvn clean test '-Dtest=InMemoryA2aTaskStoreTest'
mvn clean test '-Dtest=McpServerVerticleTest'
mvn clean test '-Dtest=MainTest'
```

## Full verification

Run the complete local gate:

```powershell
mvn clean verify 2>&1 | Tee-Object -FilePath logs/full-verify.log
.\scripts\verify-packaging.ps1 2>&1 |
  Tee-Object -FilePath logs/packaging-verification.log
.\scripts\run-conformance.ps1 2>&1 |
  Tee-Object -FilePath logs/mcp-conformance.log
```

`mvn clean verify` enforces 85% line and 70% branch coverage. Do not lower the
threshold or exclude new application code to make a change pass.

The packaging script proves consecutive shaded builds are repeatable, validates
the main class, detects application/dependency class overlap, and rejects
dependency module descriptors. The conformance script exercises the pinned
official MCP scenarios using test-only tools.

When changing dependencies, also audit for prohibited mocking artifacts:

```powershell
mvn dependency:tree '-Dincludes=org.mockito:*'
```

## Change checklists

### MCP transport or tool change

- Confirm the applicable MCP revision and standard compatibility behavior.
- Test status, headers, JSON-RPC ID/code/message, and side effects.
- Preserve body, schema, time, concurrency, and response bounds.
- Test cancellation and cleanup if work can outlive the request.
- Run MCP conformance when wire behavior may change.

### A2A transport or agent change

- Confirm behavior against A2A v1.0 and the normative protobuf schema.
- Test `A2A-Version`, media type, HTTP status, ErrorInfo reason, and metadata.
- Test task/context invariants and terminal immutability.
- Test SSE order, backpressure, disconnect cleanup, and terminal closure.
- Keep the Agent Card consistent with implemented capabilities.

### Runtime or packaging change

- Test configuration precedence and invalid values.
- Preserve MCP-only startup when A2A is disabled.
- Validate `ServiceLoader` resources in the shaded JAR.
- Run repeat packaging and inspect startup/shutdown logs.

## Documentation map

- [User guide](user-guide.md): running, calling, securing, and troubleshooting
- [A2A guide](a2a.md): focused A2A route and SPI reference
- [Coding principles](coding-principles.md): detailed Vert.x and test rules
- [README](../README.md): capability summary and configuration reference
- [Handover](handover-2026-08-14.md): implementation history and current status
