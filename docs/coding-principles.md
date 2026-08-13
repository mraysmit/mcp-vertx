# mcp-vertx coding principles

These principles apply to production code, tests, examples, and documentation in
this repository. They are intentionally specific about Vert.x 5 because small
misunderstandings of its execution and `Future` model can cause stalled event
loops, hidden failures, leaked work, or responses completed from the wrong
thread.

The project currently targets Java 25, Vert.x 5.1.x, and the stateless MCP
protocol revision declared by `McpServerVerticle`. When code and this document
disagree with the applicable MCP or Vert.x specification, the specification is
the source of truth and this document must be corrected.

## Core engineering rules

1. Understand the request path before changing it: transport security, body
   collection, JSON-RPC parsing, MCP metadata validation, dispatch, tool
   execution, result mapping, and response serialization are separate layers.
2. Preserve failure information until the layer responsible for translating it.
3. Never block a standard Vert.x event loop.
4. Bound all untrusted input and output by bytes, time, concurrency, and schema
   complexity where applicable.
5. Treat tool providers as extension code: validate their definitions at
   registration and defend against runtime failures.
6. Test externally observable behavior, including HTTP status, JSON-RPC code,
   request ID, headers, body, and side effects.
7. Do not make a test pass by skipping work, swallowing a failure, or waiting an
   arbitrary amount of time.

## Investigation before implementation

Before changing protocol behavior:

- Identify the normative MCP revision used by the server.
- Trace the full request path and existing tests.
- Separate a transport error from a JSON-RPC protocol error and a tool execution
  error.
- Check the Vert.x 5 API contract instead of assuming Vert.x 4 callback behavior.
- Reproduce the failure with a focused test before implementing the fix.

Avoid symptom fixes such as catching every exception, returning an empty result,
or adding a timer to make a race disappear. Such changes usually convert a
visible defect into silent data loss.

## Vert.x 5 execution model

### Standard verticles run on an event loop

A standard verticle is assigned a context tied to an event-loop thread. Handlers
registered through Vert.x APIs from that context normally execute on the same
context. This permits simple single-threaded state inside one verticle instance,
but only while code stays on that context.

Do not perform any of the following on the event loop:

- `Thread.sleep(...)`
- `Future.await()`
- `CompletableFuture.get()` or `join()`
- blocking file, socket, process, or database calls
- large CPU-bound validation or serialization
- unbounded loops or polling

`Future.await()` is valid from a Vert.x virtual-thread verticle or an appropriate
non-Vert.x thread. This project uses a standard event-loop verticle, so `await()`
must not appear in its request path.

### Context affinity can be lost at API boundaries

Java `CompletionStage` callbacks run on the thread that completes the stage.
When adapting one to Vert.x, provide the context explicitly:

```java
Context context = Vertx.currentContext();
Future<Result> result = Future.fromCompletionStage(stage, context);
```

Do not modify verticle-confined state or an HTTP response directly from an
arbitrary provider thread. Adapt the completion to the request context first.

Tool implementations must also be safe if the server is later deployed with
multiple verticle instances. A single-context guarantee is not a global
single-threading guarantee.

### Use `executeBlocking` deliberately

Short blocking operations can be moved to the worker pool:

```java
return vertx.executeBlocking(() -> blockingOperation());
```

Vert.x 5 runs `executeBlocking` calls from the same context serially by default.
If independent calls may run concurrently, request unordered execution
explicitly and confirm the underlying operation is thread-safe:

```java
return vertx.executeBlocking(() -> blockingOperation(), false);
```

Gotchas:

- Moving work to the shared worker pool does not make it cheap.
- Long-running or unbounded work can exhaust the worker pool.
- CPU-heavy work should use a bounded, purpose-specific executor or explicit
  concurrency limit.
- Preserve deadlines and cancellation signals across the worker boundary.

## Verticle lifecycle in Vert.x 5

Vert.x 5 retains `AbstractVerticle`, but `VerticleBase` is the future-native
contract and should be preferred for new code or a deliberate lifecycle
refactor.

```java
public final class McpServerVerticle extends VerticleBase {
  private long cleanupTimerId = -1;

  @Override
  public Future<?> start() {
    Router router = createRouter();
    return vertx.createHttpServer()
        .requestHandler(router)
        .listen(port, host)
        .onSuccess(server -> {
          cleanupTimerId = vertx.setPeriodic(120_000, ignored -> cleanup());
        })
        .mapEmpty();
  }

  @Override
  public Future<?> stop() {
    if (cleanupTimerId >= 0) {
      vertx.cancelTimer(cleanupTimerId);
    }
    return Future.succeededFuture();
  }
}
```

Lifecycle rules:

- The future returned by `start()` represents actual readiness. Do not complete
  it before the server has bound successfully.
- The future returned by `stop()` represents required cleanup completion.
- Cancel periodic timers and close resources not managed automatically by the
  deployment.
- Vert.x automatically closes HTTP servers created by a verticle when it is
  undeployed; do not duplicate lifecycle ownership without a reason.
- If `AbstractVerticle` remains in use, complete or fail its lifecycle promise
  exactly once. Prefer `tryComplete`/`tryFail` where completion can race.

## Vert.x `Future` composition

### Use `compose` for asynchronous sequencing

```java
return validateRequest(request)
    .compose(validated -> invokeTool(validated))
    .compose(result -> encodeResponse(validated.id(), result));
```

If an upstream future fails, later success mappers are skipped and the failure
propagates automatically.

### Use `map` for synchronous value conversion

```java
return server.listen(port, host)
    .map(server -> server.actualPort());
```

Use `mapEmpty()` when the successful value is intentionally discarded. Do not
use `compose` merely to wrap a synchronous value in `succeededFuture`.

### Terminal observers do not define workflow order

`onSuccess`, `onFailure`, and `onComplete` observe completion. They are useful at
the edge of a workflow for responding, logging, metrics, or completing a test.
They are not a substitute for composition.

```java
invokeTool(request)
    .onSuccess(result -> sendResult(response, request.id(), result))
    .onFailure(error -> sendFailure(response, request.id(), error));
```

`onComplete` is not forbidden. Use it when both outcomes genuinely belong in one
observer or when bridging to a legacy promise. Prefer `onSuccess`/`onFailure`
when separate branches are clearer.

Do not register several terminal handlers and rely on their invocation order;
Vert.x does not guarantee ordering among them. Compose ordered work instead.

### `recover` is valid, but it changes failure into another outcome

`recover` is the failure-side counterpart of `compose`. Use it only when the
failure is intentionally mapped to a genuine alternate future.

Legitimate fallback:

```java
return primaryLookup(key)
    .recover(error -> isPrimaryUnavailable(error)
        ? secondaryLookup(key)
        : Future.failedFuture(error));
```

Legitimate MCP boundary mapping:

```java
return invocation
    .map(this::successfulToolResult)
    .recover(error -> Future.succeededFuture(safeToolErrorResult(error)));
```

The second example is correct only because MCP deliberately represents tool
execution failures as a successful JSON-RPC result with `isError: true`. Unknown
tools, malformed requests, server bugs, and transport failures must not be
silently converted into tool errors.

Never fabricate ordinary data on failure:

```java
// Wrong: clients cannot distinguish failure from an empty tool registry.
return loadTools().recover(error -> Future.succeededFuture(List.of()));
```

Never use `recover` merely to log and rethrow. Use `onFailure` for observation:

```java
return operation()
    .onFailure(error -> LOG.warn("Operation failed", error));
```

### Use `transform` when both outcomes must be mapped

`transform` is appropriate when success and failure both become a different
domain type or when combining operation and cleanup outcomes.

```java
return healthProbe().transform((value, error) -> {
  HealthStatus status = error == null
      ? HealthStatus.up(value)
      : HealthStatus.down(safeSummary(error));
  return Future.succeededFuture(status);
});
```

The conversion must be explicit in the return type. Do not hide an error inside
an object that otherwise looks like ordinary successful data.

### `eventually` is best-effort finally, not strict cleanup

`eventually` always invokes its mapper and preserves the original success or
failure. The cleanup future's outcome does not change that original outcome.

```java
return useResource()
    .eventually(resource::close);
```

This is suitable when cleanup must be attempted but a cleanup failure must not
replace the original result. Observe and log cleanup failures inside the cleanup
operation when needed.

Do not use `eventually` when cleanup failure must fail an otherwise successful
operation. In that case use `transform` and combine both outcomes explicitly,
adding a cleanup failure as suppressed when an original failure already exists.

### `Future.timeout` does not cancel the underlying operation

```java
Future<JsonObject> bounded = invocation.timeout(30, TimeUnit.SECONDS);
```

This guarantees that `bounded` completes within the deadline. It does not imply
that `invocation` or its downstream network/database work has stopped. A timed
out tool may continue consuming resources and its later completion is ignored by
the wrapper.

Therefore:

- Pass a deadline and cancellation signal in the tool context.
- Cancel downstream HTTP/database work when the API supports it.
- Bound active and queued invocations independently of request rate.
- Treat client disconnect as cancellation when the transport makes it
  detectable.
- Test that timeout responses and provider cleanup both happen.

### Use promises only for callback adaptation or externally completed work

Prefer returning the future supplied by the underlying operation. Create a
`Promise` only when some other callback, event, or lifecycle signal must complete
it.

If several events may win a completion race, use `tryComplete` and `tryFail`.
Never allow a promise to remain incomplete on an exception path.

## Combining futures

Choose the combinator based on the required semantics:

```java
Future.all(first, second, third);  // fail fast when any fails
Future.join(first, second, third); // wait for all, then succeed or fail
Future.any(first, second, third);  // succeed when one succeeds
```

Gotchas:

- `all` is appropriate for a strict dependency set when early failure is useful.
- `join` waits for every input, but it does not turn failures into success. Keep
  references to the component futures if every individual outcome is required.
- `any` is not cancellation: losing operations may continue after one succeeds.
- None of these methods provides a concurrency limit. Do not create thousands
  of futures and expect a composite future to provide backpressure.

## Timers and readiness

Timers represent elapsed time, not readiness.

```java
// Wrong: deployment may still be starting after 100 ms.
vertx.setTimer(100, ignored -> testContext.completeNow());

// Correct: completion is coupled to the operation under test.
deployServer()
    .compose(server -> callDiscover(server.actualPort()))
    .onSuccess(ignored -> testContext.completeNow())
    .onFailure(testContext::failNow);
```

Use timers for actual time-based behavior such as expiry, backoff, or scheduled
cleanup. Retain and cancel periodic timer IDs during shutdown.

## Vert.x Web and MCP request handling

### Handler order is part of the security model

For the MCP endpoint, use this conceptual order:

1. Cheap connection-level checks: `Origin`, authentication, and rate limiting.
2. Cheap media-type and content-negotiation checks.
3. Bounded body collection and JSON decoding.
4. JSON-RPC and MCP metadata validation.
5. Dispatch and tool execution.
6. Central handling for unexpected routing failures.

The body handler must run before code reads `ctx.body()`, but expensive body
collection should not happen before a request can be rejected by cheap security
checks.

Vert.x 5 assigns handlers to phases and rejects a `BODY` handler registered
after a normal user handler on the same route. When a user-phase media check
must precede body collection, register two matching routes in order: the first
performs the cheap check and calls `next()`, while the second owns the
`BodyHandler` and request logic.

Configure `BodyHandler` for the endpoint:

```java
BodyHandler.create(false)
    .setBodyLimit(maxBodyBytes)
    .setMergeFormAttributes(false);
```

MCP accepts JSON, not file uploads or merged form parameters.

### Separate expected protocol errors from unexpected failures

Expected errors need precise JSON-RPC IDs and codes, so translate them explicitly
at the protocol boundary. Examples include parse error, invalid request, invalid
params, header mismatch, unsupported protocol version, and unknown method.

Unexpected router failures should go through a centralized failure handler. That
handler must:

- avoid writing if the response has already ended;
- preserve required HTTP status such as 413;
- return a bounded, sanitized JSON-RPC error;
- log internal detail with a correlation ID;
- never expose stack traces or raw exception messages.

### End each response exactly once

Timeout, client disconnect, provider completion, and error mapping can race.
Structure the request as one composed future with one response boundary rather
than letting independent callbacks write competing responses. Check response
state in central failure paths.

### Bound the encoded envelope

Measure the complete UTF-8 encoded JSON-RPC response, not only a component placed
inside it. A structured result duplicated as text may more than double the wire
size, and JSON escaping can increase it further.

## MCP failure taxonomy

Keep these categories distinct:

| Category | Example | Representation |
| --- | --- | --- |
| Transport | invalid Origin, unsupported media type, body too large | HTTP error, optionally JSON-RPC error |
| JSON-RPC/MCP protocol | malformed request, unknown tool, header mismatch | JSON-RPC `error` with the request ID when known |
| Tool execution | upstream API failure, rejected business input | JSON-RPC `result` with `isError: true` |
| Server defect | null provider future, unexpected exception | sanitized internal JSON-RPC error or safe tool error according to boundary |

Only deliberately safe tool messages may be sent to clients. Log unexpected
causes internally with the tool name and correlation ID. Do not log bearer
tokens, complete arguments, mirrored parameter headers, or full tool results.

## Logging principles

Use INFO for operational milestones that remain useful during normal service:
startup and shutdown, accepted request methods, request outcomes, tool-call
outcomes, bounded-capacity rejection, and deliberately safe failure categories.
Use SLF4J DEBUG for routing, schema-validation, concurrency, response size,
timing, and test diagnostics. Configure Vert.x to use its SLF4J logging delegate
so framework and application records share one backend and format.

Logging is observation, not workflow control. Attach terminal observers to the
future that owns the operation, preserve its success or failure, and never add
blocking work to produce a log message. Prefer supplier-based DEBUG messages so
disabled diagnostics do not eagerly allocate or serialize data.

Every log field derived from a request must be bounded and stripped of control
characters. Never log authentication credentials, complete arguments, mirrored
parameter values, client input-response data, raw request or response bodies,
or complete tool results. Tool failures should include the validated tool name
and server-generated correlation ID; unexpected causes should retain their
stack trace only in internal logs.

## Tool provider contracts

At registration time:

- require a stable, valid, unique tool name;
- copy and compile the input schema;
- reject unsupported schema dialects rather than interpreting them as another
  dialect;
- reject unsafe external references unless an explicitly secured resolver is
  introduced;
- bound schema bytes, nesting, node count, composition branches, and regex size;
- precompute any header-mirroring descriptors.

At invocation time:

- validate arguments before calling the provider;
- pass defensive copies of mutable JSON objects;
- require a non-null `Future` and a valid result;
- apply a deadline, output-size limit, and concurrency limit;
- distinguish safe provider errors from internal exceptions;
- preserve correlation information for logs and metrics.

A provider's returned `Future` must represent completion of the work, not merely
submission of fire-and-forget work.

## Fire-and-forget work

Every future must be returned, composed, or deliberately observed.

```java
// Wrong: deployment failure is lost.
vertx.deployVerticle(server);

// Correct: return it or handle both outcomes.
return vertx.deployVerticle(server).mapEmpty();
```

If work is intentionally detached, document the ownership and install failure
reporting, cancellation, and shutdown behavior. Detached work must never be the
hidden implementation of a request that claims completion.

## Testing principles

### Classify tests by boundary

- Unit tests cover pure parsing, schema safety, settings, registries, encoding,
  and error mapping without starting a server.
- Vert.x integration tests deploy the real router on port `0` and exercise the
  endpoint with the real Vert.x HTTP client.
- Packaging tests start the shaded JAR and verify ServiceLoader discovery and
  the public MCP endpoint.
- Use Testcontainers only when a future tool provider genuinely depends on a
  real external service such as PostgreSQL. Do not inherit a database-mandatory
  policy from an unrelated project.

### Vert.x test rules

Use `VertxExtension` and `VertxTestContext` for asynchronous tests:

```java
@Test
void discover_returns_capabilities(Vertx vertx, VertxTestContext ctx) {
  deploy(vertx)
      .compose(server -> postDiscover(vertx, server.actualPort()))
      .onSuccess(response -> ctx.verify(() -> {
        assertEquals(200, response.statusCode());
        ctx.completeNow();
      }))
      .onFailure(ctx::failNow);
}
```

Rules:

- Complete or fail the test context on every path.
- Put assertions in `ctx.verify(...)` when they run asynchronously.
- Bind to port `0`; do not allocate fixed test ports.
- Do not use sleeps, timer-based readiness, or polling without a bounded,
  event-driven reason.
- Verify HTTP status, content type, JSON-RPC ID, result/error shape, and relevant
  headers.
- Keep negative tests for every validation and security boundary.
- Assert that limits are measured in UTF-8 bytes using non-ASCII and escaped
  inputs.
- For timeout tests, also verify underlying cancellation or cleanup where the
  provider contract supports it.

### Test the packaged application

Unit and in-process integration tests do not validate shading or service metadata.
At least one smoke test should:

1. build the shaded JAR;
2. load a sample tool through `ServiceLoader`;
3. start the application on an ephemeral port;
4. call `server/discover`, `tools/list`, and `tools/call`;
5. shut the process down and verify a clean exit.

### Verify that tests actually ran

Do not treat a successful Maven invocation as proof unless the expected test
count ran. The standard local gate is:

```powershell
mvn clean verify
```

Inspect Surefire output for executed tests, failures, errors, and skipped tests.

## Dependency and build discipline

- Keep all Vert.x modules on one version through the project dependency chain.
- Do not pin Netty independently unless a documented compatibility issue
  requires it.
- Avoid mixing Vert.x 4 and Vert.x 5 modules.
- Treat shaded-JAR overlap warnings as review items, especially service files,
  logging bindings, Jackson versions, and `module-info.class`.
- Preserve `META-INF/services` entries with the shade plugin's services
  transformer.
- Validate configuration before binding the HTTP server and fail deployment with
  a clear message.

## Review checklist

### Vert.x

- Does every asynchronous branch return, compose, or observe its future?
- Can any standard event-loop path block or perform large CPU work?
- Is context preserved when adapting external completion stages?
- Is `recover` performing an intentional domain or protocol conversion?
- Is `eventually` acceptable even though cleanup failure cannot replace the
  original outcome?
- Does every timeout have a cancellation/resource-control story?
- Are timer and lifecycle resources cleaned up?
- Could multiple callbacks end the same response?

### MCP and security

- Is the behavior valid for the declared MCP revision?
- Are mirrored headers decoded and compared to their body source?
- Are protocol errors distinct from tool execution errors?
- Are all returned error messages safe for an untrusted client?
- Are request bodies, schemas, concurrent calls, execution time, and final wire
  responses bounded?
- Are mutable provider inputs and outputs defensively copied?

### Tests and documentation

- Is there a regression test for the reported failure?
- Does the test exercise the real layer affected by the change?
- Does it avoid sleeps and false-success recovery?
- Did `mvn clean verify` run the expected tests?
- Do README and HTTP examples match the implemented protocol?

## Default patterns

Normal pipeline:

```java
return parse(request)
    .compose(this::validate)
    .compose(this::dispatch)
    .onFailure(error -> LOG.warn("Request failed", error));
```

Tool boundary with deliberate MCP error conversion:

```java
return tool.invoke(arguments.copy(), context)
    .timeout(timeoutMs, TimeUnit.MILLISECONDS)
    .map(this::successfulToolResult)
    .recover(error -> Future.succeededFuture(safeToolErrorResult(error)));
```

Strict fallback:

```java
return primary()
    .recover(error -> canFallback(error)
        ? secondary()
        : Future.failedFuture(error));
```

Best-effort cleanup that preserves the original outcome:

```java
return operation()
    .eventually(() -> closeBestEffort()
        .onFailure(error -> LOG.warn("Cleanup failed", error)));
```

The governing idea is simple: compose the real work, preserve honest outcomes,
translate failures only at an explicit boundary, and never let convenience hide
resource consumption or protocol behavior.
