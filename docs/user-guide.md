# mcp-vertx user guide

This guide is for operators and client developers who want to run, configure,
observe, and call the server. For implementing MCP tools or A2A agents, see the
[developer guide](developer-guide.md).

## What the server provides

One JVM can expose two complementary protocols on independent HTTP listeners:

| Transport | Default endpoint | Default state |
| --- | --- | --- |
| MCP Streamable HTTP | `http://127.0.0.1:3001/mcp` | enabled |
| A2A v1.0 HTTP+JSON | `http://127.0.0.1:3002/a2a` | disabled |
| A2A Agent Card | `http://127.0.0.1:3002/.well-known/agent-card.json` | available when A2A is enabled |

MCP exposes tools to compatible model clients. A2A exposes one remotely
addressable agent, including asynchronous tasks and streaming events. Enabling
A2A does not alter the MCP endpoint.

## Requirements

- Java 25
- Maven 3.9 or later when building from source
- An MCP tool-provider JAR if the server should expose tools
- Exactly one A2A agent-provider JAR if A2A is enabled

Check the installed runtimes:

```powershell
java -version
mvn -version
```

## Build and start

Build and verify the executable JAR:

```powershell
mvn clean verify
```

Start MCP with its safe loopback defaults:

```powershell
java -jar target/mcp-vertx-0.3.0-SNAPSHOT.jar
```

Startup is complete when the log contains `MCP server started`. With no tool
provider installed, this is still a valid server and `tools/list` returns an
empty list.

Press `Ctrl+C` to stop it. The shutdown hook closes the Vert.x runtime and logs
the shutdown outcome.

## Configuration precedence

Every standalone setting has a Java system property and an environment-variable
form. Resolution order is:

1. non-blank Java system property;
2. non-blank environment variable;
3. built-in default.

For example, these are equivalent:

```powershell
$env:MCP_PORT = '4101'
java -jar target/mcp-vertx-0.3.0-SNAPSHOT.jar
```

```powershell
java -Dmcp.port=4101 -jar target/mcp-vertx-0.3.0-SNAPSHOT.jar
```

The complete setting table is in the [README](../README.md#configuration).

## Configure MCP

The most common MCP settings are:

| Environment variable | Purpose |
| --- | --- |
| `MCP_HOST` | Bind address; defaults to `127.0.0.1` |
| `MCP_PORT` | Listener port; defaults to `3001` |
| `MCP_BASE_PATH` | Prefix before `/mcp` |
| `MCP_AUTH_TOKEN` | Fixed bearer token for private deployments |
| `MCP_ALLOWED_ORIGINS` | Comma-separated browser origins |
| `MCP_HEALTH_ENABLED` | Enables liveness and readiness routes |
| `MCP_MAX_BODY_BYTES` | Maximum HTTP request body |
| `MCP_MAX_RESPONSE_BYTES` | Maximum serialized response |

If `MCP_BASE_PATH=/services`, the MCP URL becomes `/services/mcp`; health
routes become `/services/health/live` and `/services/health/ready`.

### Call MCP manually

The repository includes [`mcp-vertx.http`](../mcp-vertx.http), containing
complete discovery, tool-list, and tool-call requests for an IDE HTTP client.

For a standard initialization request with PowerShell:

```powershell
$headers = @{
  Accept = 'application/json, text/event-stream'
  'Content-Type' = 'application/json'
}
$body = @{
  jsonrpc = '2.0'
  id = 1
  method = 'initialize'
  params = @{
    protocolVersion = '2025-11-25'
    capabilities = @{}
    clientInfo = @{ name = 'manual-client'; version = '1.0.0' }
  }
} | ConvertTo-Json -Depth 8
Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:3001/mcp' `
  -Headers $headers -Body $body
```

The server also supports its extended stateless `2026-07-28` profile. Use the
checked-in HTTP examples when testing that profile because every request must
carry matching protocol, method, client-information, and capability metadata.

## Configure A2A

A2A is opt-in and requires exactly one implementation of
`dev.mars.a2a.A2aAgent` on the runtime classpath.

```powershell
$env:A2A_ENABLED = 'true'
java -cp "target/mcp-vertx-0.3.0-SNAPSHOT.jar;C:\agents\example-agent.jar" `
  dev.mars.mcp.Main
```

If no agent or more than one agent is discovered, startup fails rather than
selecting one unpredictably.

Common A2A settings are:

| Environment variable | Purpose |
| --- | --- |
| `A2A_ENABLED` | Enables the A2A listener |
| `A2A_HOST` | Bind address; defaults to `127.0.0.1` |
| `A2A_PORT` | Listener port; defaults to `3002` |
| `A2A_BASE_PATH` | Operational route prefix; defaults to `/a2a` |
| `A2A_AUTH_TOKEN` | Fixed bearer token for operational routes |
| `A2A_MAX_BODY_BYTES` | Maximum message/cancellation request body |

The Agent Card discovery route is always
`/.well-known/agent-card.json`; it does not move under `A2A_BASE_PATH`.

### Discover the agent

```powershell
Invoke-RestMethod `
  -Uri 'http://127.0.0.1:3002/.well-known/agent-card.json'
```

The returned card tells clients which protocol interface, version, content
modes, skills, streaming capabilities, and security schemes the agent supports.

### Send a message

```powershell
$headers = @{
  'A2A-Version' = '1.0'
  Accept = 'application/a2a+json'
  'Content-Type' = 'application/a2a+json'
}
$body = @{
  message = @{
    role = 'ROLE_USER'
    messageId = 'message-001'
    parts = @(@{ text = 'Summarize the deployment status' })
  }
} | ConvertTo-Json -Depth 8
Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:3002/a2a/message:send' `
  -Headers $headers -Body $body
```

An immediate agent returns a `message` wrapper. Stateful work returns a `task`
wrapper containing the task ID, context ID, and current status.

### Retrieve and list tasks

```powershell
$headers = @{ 'A2A-Version' = '1.0'; Accept = 'application/a2a+json' }
Invoke-RestMethod -Headers $headers `
  -Uri 'http://127.0.0.1:3002/a2a/tasks/task-123'

Invoke-RestMethod -Headers $headers `
  -Uri 'http://127.0.0.1:3002/a2a/tasks?pageSize=20&includeArtifacts=true'
```

List queries support `contextId`, `status`, `pageSize`, `pageToken`,
`historyLength`, `statusTimestampAfter`, and `includeArtifacts`. Continue with
`nextPageToken` until it is an empty string.

### Cancel a task

```powershell
$headers = @{
  'A2A-Version' = '1.0'
  'Content-Type' = 'application/a2a+json'
}
Invoke-RestMethod -Method Post -Headers $headers -Body '{}' `
  -Uri 'http://127.0.0.1:3002/a2a/tasks/task-123:cancel'
```

Terminal tasks are immutable and cannot be canceled.

### Consume SSE streams

Use a client that displays response chunks as they arrive. With `curl.exe`:

```powershell
curl.exe -N `
  -H "A2A-Version: 1.0" `
  -H "Accept: text/event-stream" `
  "http://127.0.0.1:3002/a2a/tasks/task-123:subscribe"
```

The first subscription event is the current task snapshot. Subsequent events
carry status or artifact updates, and the stream closes after a terminal status.
Message streaming uses `POST /a2a/message:stream` with the same message body as
`message:send` and `Accept: text/event-stream`.

## Authentication and network exposure

Both listeners default to loopback. A non-loopback binding is rejected unless
authentication is configured.

For a private MCP deployment:

```powershell
$env:MCP_HOST = '0.0.0.0'
$env:MCP_AUTH_TOKEN = '<long-random-value>'
```

For a private A2A deployment:

```powershell
$env:A2A_HOST = '0.0.0.0'
$env:A2A_AUTH_TOKEN = '<long-random-value>'
```

Send either token as `Authorization: Bearer <value>`. A2A discovery remains
public, while A2A operational routes require the token. Never place these
listeners directly on an untrusted network; terminate TLS at a reverse proxy.

MCP also has a standards-oriented OAuth resource-server mode. See
[Security defaults](../README.md#security-defaults) and the local
[OAuth/TLS integration instructions](../README.md#local-oauth-and-tls-integration).
The A2A fixed token is intended for private deployments; public deployments
should enforce the schemes advertised by the Agent Card at a gateway.

## Health and readiness

Enable MCP health routes with:

```powershell
$env:MCP_HEALTH_ENABLED = 'true'
```

Then query:

```powershell
Invoke-WebRequest 'http://127.0.0.1:3001/health/live'
Invoke-WebRequest 'http://127.0.0.1:3001/health/ready'
```

Health routes use the same authentication policy as MCP requests.

## Logging

The executable JAR contains its Logback configuration. No logging command-line
property is necessary. Defaults are DEBUG for the MCP and A2A application
packages, DEBUG for Vert.x, INFO for access records, and INFO for Netty.

Tune levels through environment variables:

```powershell
$env:MCP_LOG_LEVEL = 'INFO'
$env:A2A_LOG_LEVEL = 'TRACE'
$env:MCP_HTTP_LOG_LEVEL = 'DEBUG'
$env:VERTX_LOG_LEVEL = 'INFO'
$env:VERTX_WEB_LOG_LEVEL = 'INFO'
$env:NETTY_LOG_LEVEL = 'WARN'
$env:ROOT_LOG_LEVEL = 'INFO'
```

Logs include lifecycle, request method/path/status, timing, protocol decisions,
task/tool milestones, and safe failure classifications. They intentionally omit
authorization values, tool arguments, message bodies, and tool-result bodies.

To preserve a run outside Maven's cleaned `target` directory:

```powershell
New-Item -ItemType Directory -Force logs | Out-Null
java -jar target/mcp-vertx-0.3.0-SNAPSHOT.jar 2>&1 |
  Tee-Object -FilePath logs/server.log
```

## Troubleshooting

### A2A reports that exactly one provider is required

Confirm the agent JAR is on the runtime classpath and contains:

```text
META-INF/services/dev.mars.a2a.A2aAgent
```

The file must name one public implementation with an accessible no-argument
constructor. Remove duplicate provider JARs.

### Public binding is rejected

Set the corresponding bearer token, or return the listener to `127.0.0.1`.
MCP OAuth also satisfies MCP's public-bind requirement.

### A2A returns `VERSION_NOT_SUPPORTED`

Send `A2A-Version: 1.0`. A missing version is interpreted as legacy v0.3 and is
rejected by this v1-only transport.

### MCP browser requests return 403

Add the complete browser origin to `MCP_ALLOWED_ORIGINS`. Wildcards are
intentionally rejected. Non-browser clients normally should not send `Origin`.

### Requests return 413 or responses fail because of size

Review `MCP_MAX_BODY_BYTES`, `MCP_MAX_RESPONSE_BYTES`, and
`A2A_MAX_BODY_BYTES`. Increase bounds deliberately; they are denial-of-service
controls, not merely convenience limits.

### A task disappeared after restart

The standalone A2A server currently uses an in-memory task store. Durable or
clustered deployments must embed `A2aServerVerticle` with a persistent
`A2aTaskStore` implementation.

## Current A2A limits

- HTTP+JSON is implemented; JSON-RPC and gRPC bindings are not.
- Push-notification configuration is not implemented.
- Authenticated extended Agent Cards are not implemented.
- The default task store is process-local.

See the focused [A2A architecture guide](a2a.md) for the protocol surface and
extension boundaries.
