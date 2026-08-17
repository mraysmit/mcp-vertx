# A2A v1.0 transport

## Architecture

The A2A implementation is a sibling transport to MCP rather than an MCP tool
adapter. This preserves the protocols' intended roles: MCP exposes tools and
context to a model or agent, while A2A lets independently addressable agents
collaborate.

The layers are deliberately small:

| Layer | Responsibility |
| --- | --- |
| `A2aAgent` | Application-owned agent behavior and Agent Card |
| `A2aServerVerticle` | Vert.x HTTP+JSON routes, versioning, SSE, auth, errors |
| `A2aJsonCodec` | Official generated protobuf/ProtoJSON wire boundary |
| `A2aTaskStore` | Task snapshot, query, update, and subscription boundary |
| `InMemoryA2aTaskStore` | Thread-safe process-local default implementation |

The public SPI uses official `org.a2aproject.sdk.spec` records and Vert.x
`Future` values. Agent implementations therefore do not depend on routing,
HTTP response objects, or protobuf-generated transport classes.

## HTTP+JSON surface

The discovery document is always served at the standard well-known path. The
remaining routes are relative to `a2a.basePath`, which defaults to `/a2a`.

| Method | Route | Operation |
| --- | --- | --- |
| `GET` | `/.well-known/agent-card.json` | Discover the public Agent Card |
| `POST` | `/a2a/message:send` | Send a message |
| `POST` | `/a2a/message:stream` | Send a message and receive SSE events |
| `GET` | `/a2a/tasks/{id}` | Retrieve a task |
| `GET` | `/a2a/tasks` | List/filter tasks with cursor pagination |
| `POST` | `/a2a/tasks/{id}:cancel` | Cancel a non-terminal task |
| `GET` | `/a2a/tasks/{id}:subscribe` | Snapshot plus live SSE task updates |

Operational requests must select v1.0 with `A2A-Version: 1.0`. SSE emitters
return `Future<Void>` so an agent cannot outrun the Vert.x response stream.
Every task update is applied to the task store before it is written to the
client.

## Register an agent

Implement `dev.mars.a2a.A2aAgent`. `sendMessage` may return a protocol
`Message` for an immediate response or a `Task` for stateful work. Override
`streamMessage` and `cancelTask` only when the Agent Card advertises those
capabilities.

```java
public final class ExampleAgent implements A2aAgent {
  @Override
  public AgentCard agentCard() {
    AgentSkill skill = AgentSkill.builder()
        .id("example")
        .name("Example work")
        .description("Processes example work")
        .tags(List.of("example"))
        .build();
    return AgentCard.builder()
        .name("Example agent")
        .description("Processes example work")
        .version("1.0.0")
        .capabilities(AgentCapabilities.builder().streaming(true).build())
        .supportedInterfaces(List.of(new AgentInterface(
            "HTTP+JSON", "http://127.0.0.1:3002/a2a", null, "1.0")))
        .defaultInputModes(List.of("text/plain"))
        .defaultOutputModes(List.of("text/plain"))
        .skills(List.of(skill))
        .build();
  }

  @Override
  public Future<EventKind> sendMessage(MessageSendParams request) {
    // Invoke real domain/application services here and return a Message or Task.
    return Future.failedFuture("not implemented");
  }
}
```

Publish exactly one provider in the provider JAR:

```text
META-INF/services/dev.mars.a2a.A2aAgent
```

The file contains the fully qualified implementation class. The shaded JAR
merges service descriptors. Startup fails clearly if A2A is enabled with zero
or multiple providers.

```powershell
$env:A2A_ENABLED = 'true'
java -cp "target/mcp-vertx-0.3.0-SNAPSHOT.jar;path\to\agent.jar" dev.mars.mcp.Main
```

## Security and deployment

The Agent Card remains public for discovery. Operational routes can be guarded
with `a2a.authToken`; binding A2A to a non-loopback address is rejected unless
that token is configured. Authorization values and message bodies are not
written to logs. Use TLS at a reverse proxy whenever requests cross a trusted
local boundary, and make the Agent Card's advertised security schemes match
the deployment's actual authentication policy.

The fixed token is intended for private deployments. A production public agent
should normally enforce the security scheme advertised in its Agent Card at a
gateway or through a future pluggable A2A authorization policy.

## Current limits

- The default task store is process-local and is lost on restart. Implement
  `A2aTaskStore` for durable or clustered deployments and pass it to the server
  verticle when composing an embedded runtime.
- Push-notification configuration and authenticated extended Agent Cards are
  not implemented yet.
- The transport currently exposes the HTTP+JSON binding, not JSON-RPC or gRPC.
- Agent-specific authorization decisions remain the agent or gateway's
  responsibility.

The protocol reference is the [A2A v1.0 documentation](https://a2a-protocol.org/latest/),
with the [normative protobuf definition](https://github.com/a2aproject/A2A/blob/main/specification/a2a.proto)
used through the official Java SDK.
