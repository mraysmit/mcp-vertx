# mcp-vertx

A current-generation Model Context Protocol server framework implemented in
Java 25 with Vert.x 5.

## Protocol support

- MCP protocol revision `2026-07-28`
- Stateless Streamable HTTP at `POST /mcp`
- `server/discover`, `tools/list`, and `tools/call` methods
- Per-request MCP version, method, client identity, and capability validation
- JSON Schema 2020-12 and draft-07 tool-input validation
- Native text, image, audio, resource-link, and arbitrary JSON structured results
- Multi-round-trip `input_required` results with client-capability checks
- Cooperative cancellation, execution deadlines, and bounded concurrency

The deprecated initialization/session protocol and legacy HTTP+SSE endpoints
have been removed. `GET /mcp` and `DELETE /mcp` return `405 Method Not Allowed`.

## Build and run

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-25'
mvn clean verify
java -jar target/mcp-vertx-0.3.0-SNAPSHOT.jar
```

The server binds to `127.0.0.1:3001` by default. With no external tool
providers on the classpath, `tools/list` returns an empty list.

## Logging

The server uses the standard Vert.x 5 logging integration: application code
logs through SLF4J 2, Logback is the runtime backend, and Vert.x routes its
internal logs to the same SLF4J backend. INFO records lifecycle, HTTP access
outcomes, tool execution milestones, limits, and safe failure classifications.
DEBUG adds routing, validation, concurrency, response-size, and timing detail.
Logs deliberately omit authorization values, request arguments, mirrored
parameter values, query strings, and tool-result bodies.

The bundled `src/main/resources/logback.xml` is packaged into the executable
JAR and discovered automatically by Logback. No JVM logging property or
command-line configuration is required:

```powershell
java -jar target/mcp-vertx-0.3.0-SNAPSHOT.jar
```

The defaults enable DEBUG for the application and Vert.x, INFO HTTP access
records, and INFO for Netty without enabling payload-level wire dumps. Levels
can optionally be tuned through `MCP_LOG_LEVEL`, `MCP_HTTP_LOG_LEVEL`,
`VERTX_LOG_LEVEL`, `VERTX_WEB_LOG_LEVEL`, `NETTY_LOG_LEVEL`, and
`ROOT_LOG_LEVEL` environment variables.

Maven tests automatically use DEBUG logging. A shared JUnit extension records
each test's start, outcome, duration, unique ID, tags, and execution thread;
integration helpers also record deployments and HTTP status flow.

## Security defaults

- Loopback-only binding
- Every supplied `Origin` is rejected unless explicitly allow-listed
- A bearer token is mandatory when binding to a non-loopback interface
- Per-client request rate limiting
- Bounded request bodies, schema validation, tool concurrency, execution time,
  cancellation grace, and final serialized response size

To expose the server outside the local machine:

```powershell
$env:MCP_HOST = '0.0.0.0'
$env:MCP_AUTH_TOKEN = '<generate-a-long-random-token>'
java -jar target/mcp-vertx-0.3.0-SNAPSHOT.jar
```

Send the token as `Authorization: Bearer <token>`. Use TLS at a reverse proxy
when the endpoint crosses a trusted local network boundary.

This token setting is a private deployment mechanism, not the standard MCP
OAuth authorization profile. Internet-facing servers that need interoperable
MCP authorization must place the endpoint behind an OAuth 2.1 resource server
that provides RFC 9728 Protected Resource Metadata, validates token audience
and scopes, and returns standards-compliant `WWW-Authenticate` challenges.

## Configuration

| System property | Environment variable | Default |
| --- | --- | --- |
| `mcp.port` | `MCP_PORT` | `3001` |
| `mcp.host` | `MCP_HOST` | `127.0.0.1` |
| `mcp.basePath` | `MCP_BASE_PATH` | empty |
| `mcp.allowedOrigins` | `MCP_ALLOWED_ORIGINS` | empty |
| `mcp.authToken` | `MCP_AUTH_TOKEN` | empty on loopback |
| `mcp.resourceIdField` | `MCP_RESOURCE_ID_FIELD` | `resourceId` |
| `mcp.maxRequestsPerMinute` | `MCP_MAX_REQUESTS_PER_MINUTE` | `120` |
| `mcp.maxBodyBytes` | `MCP_MAX_BODY_BYTES` | `1048576` |
| `mcp.toolTimeoutMs` | `MCP_TOOL_TIMEOUT_MS` | `30000` |
| `mcp.validationTimeoutMs` | `MCP_VALIDATION_TIMEOUT_MS` | `2000` |
| `mcp.cancellationGraceMs` | `MCP_CANCELLATION_GRACE_MS` | `250` |
| `mcp.maxConcurrentToolCalls` | `MCP_MAX_CONCURRENT_TOOL_CALLS` | `64` |
| `mcp.maxConcurrentCallsPerTool` | `MCP_MAX_CONCURRENT_CALLS_PER_TOOL` | `16` |
| `mcp.maxConcurrentValidations` | `MCP_MAX_CONCURRENT_VALIDATIONS` | `32` |
| `mcp.maxResponseBytes` | `MCP_MAX_RESPONSE_BYTES` | `1048576` |
| `mcp.healthEnabled` | `MCP_HEALTH_ENABLED` | `false` |
| `mcp.trustedProxies` | `MCP_TRUSTED_PROXIES` | empty |
| `mcp.clientAddressHeader` | `MCP_CLIENT_ADDRESS_HEADER` | `X-Forwarded-For` |

`mcp.allowedOrigins` is a comma-separated list of complete origins such as
`https://client.example,https://admin.example`. Wildcard origins are rejected.
When health endpoints are enabled, authenticated `GET /health/live` and
`GET /health/ready` endpoints are registered. Forwarded client addresses are
used only when the direct peer appears in the explicit trusted-proxy list.

## Register tools

Implement `dev.mars.mcp.tool.Tool`:

```java
public final class EchoTool implements Tool {
  public String name() {
    return "text.echo";
  }

  public String description() {
    return "Echo a message";
  }

  public JsonObject schema() {
    return new JsonObject()
        .put("type", "object")
        .put("properties", new JsonObject()
            .put("message", new JsonObject().put("type", "string")))
        .put("required", new JsonArray().add("message"))
        .put("additionalProperties", false);
  }

  public Future<JsonObject> invoke(JsonObject arguments, ToolContext context) {
    return Future.succeededFuture(
        new JsonObject().put("message", arguments.getString("message")));
  }
}
```

For the standalone launcher, list the provider class in:

```text
META-INF/services/dev.mars.mcp.tool.Tool
```

Add the provider JAR as a dependency before shading, or put it beside the server
on the runtime classpath:

```powershell
java -cp "target/mcp-vertx-0.3.0-SNAPSHOT.jar;path\to\provider.jar" dev.mars.mcp.Main
```

External JSON Schema references and the optional `x-mcp-header` schema
annotation are rejected at registration. Schemas must be self-contained.

Alternatively, embed the verticle directly:

```java
var tools = ToolRegistry.of(new EchoTool());
vertx.deployVerticle(new McpServerVerticle(tools));
```

Existing tools can keep returning `Future<JsonObject>`. Providers that need
rich content, output schemas, multi-round-trip input, or cancellation can
override `definition()` and `invokeManaged()` and return `ToolResult` values.
Throw `ToolExecutionException` only for messages deliberately safe to disclose;
all other provider failures are logged with a correlation ID and returned as a
generic tool error.

`CompleteToolResult.structured(...)` accepts any JSON value, including arrays
and primitives. For multi-round-trip results, key `inputRequests` by a
server-assigned request ID and put the MCP method and parameters in the value:

```java
return InputRequiredToolResult.requests(Map.of(
    "confirmation", new McpInputRequest("elicitation/create", new JsonObject()
        .put("mode", "form")
        .put("message", "Continue?")
        .put("requestedSchema", new JsonObject().put("type", "object")))),
    "opaque-state", new JsonObject());
```

Use `InputRequiredToolResult.stateOnly(...)` when returning an opaque retry
handle without asking the client to perform an input request.

Input schemas may place `x-mcp-header` on statically reachable primitive
properties (`string`, `integer`, or `boolean`). The server then requires the
matching `Mcp-Param-<name>` request header and compares it to the JSON argument.

See `mcp-vertx.http` for complete `server/discover`, `tools/list`, and
`tools/call` request examples with the required modern MCP headers and metadata.

## Development

See [Coding principles](docs/coding-principles.md) for the project's Vert.x 5,
MCP error-handling, testing, and extension-provider guidelines.

Run `mvn verify` to execute the tests, generate the JaCoCo report under
`target/site/jacoco`, and enforce the project coverage floor of 85% lines and
70% branches.
