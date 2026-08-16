# mcp-vertx

A current-generation Model Context Protocol server framework implemented in
Java 25 with Vert.x 5.

## Protocol support

- Extended MCP protocol revision `2026-07-28`, plus standard Streamable HTTP
  initialization compatible with MCP `2025-11-25` clients
- Stateless Streamable HTTP at `POST /mcp`
- `server/discover`, `tools/list`, and `tools/call` methods
- Per-request MCP version, method, client identity, and capability validation
- JSON Schema 2020-12 and draft-07 tool-input validation
- Native text, image, audio, resource-link, and arbitrary JSON structured results
- Multi-round-trip `input_required` results with client-capability checks
- Cooperative cancellation, execution deadlines, and bounded concurrency

Standard stateless `initialize` and `notifications/initialized` are supported;
the deprecated stateful session protocol and legacy HTTP+SSE endpoints remain
removed. `GET /mcp` and `DELETE /mcp` return `405 Method Not Allowed`.

## Build and run

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-25'
mvn clean verify
java -jar target/mcp-vertx-0.3.0-SNAPSHOT.jar
```

The server binds to `127.0.0.1:3001` by default. With no external tool
providers on the classpath, `tools/list` returns an empty list.

## Official conformance checks

The test-only conformance launcher supplies the named diagnostic tools required
by the official MCP server scenarios without adding them to the production JAR.
Run the pinned targeted `2026-07-28` checks from PowerShell:

```powershell
.\scripts\run-conformance.ps1
```

The default set covers `tools/list`, text/image/audio/embedded/mixed/error tool
results, core multi-round-trip flows, wire schemas, and DNS-rebinding protection.
Select individual scenarios with `-Scenario`, or run the complete dated
requirements set with `-Requirements`. The complete set also tests prompts,
resources, completion, Tasks, and extensions that this tools-only server does
not currently implement.

The runner pins `@modelcontextprotocol/conformance@0.2.0-alpha.11`, compiles the
fixture on the test classpath, allows only the harness's loopback Origin, and
uses `Tee-Object` to preserve harness, server, and result evidence under
`logs/`. That directory is deliberately ignored by Git and is not removed by
`mvn clean`.

The [GitHub Actions CI workflow](.github/workflows/ci.yml) runs the repeatable
packaging check, clean Maven gate, and targeted conformance set on a Windows
runner with Temurin Java 25 and Node.js 24. It uploads the Maven/packaging logs,
conformance logs/results, Surefire reports, JaCoCo report, and shaded JAR for 14
days, including when a verification step fails.

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

This token setting is a private deployment mechanism. For interoperable MCP
authorization, enable the built-in OAuth resource-server profile instead:

```powershell
$env:MCP_HOST = '0.0.0.0'
$env:MCP_OAUTH_ENABLED = 'true'
$env:MCP_OAUTH_RESOURCE_URI = 'https://mcp.example/mcp'
$env:MCP_OAUTH_ISSUER = 'https://authorization.example'
$env:MCP_OAUTH_REQUIRED_SCOPES = 'mcp:read,mcp:invoke'
java -jar target/mcp-vertx-0.3.0-SNAPSHOT.jar
```

At startup the server discovers authorization-server metadata using RFC 8414,
with OpenID Connect Discovery fallback, and loads its JWKS. It validates signed
access-token issuer, exact resource audience, expiration, and required scopes.
It publishes RFC 9728 metadata at the well-known path derived from the canonical
resource URI and includes that metadata URI in Bearer challenges. The fixed
token and OAuth modes are mutually exclusive. The public resource URI must use
HTTPS; an HTTP issuer is accepted only on a loopback address for local testing.

## Configuration

| System property | Environment variable | Default |
| --- | --- | --- |
| `mcp.port` | `MCP_PORT` | `3001` |
| `mcp.host` | `MCP_HOST` | `127.0.0.1` |
| `mcp.basePath` | `MCP_BASE_PATH` | empty |
| `mcp.allowedOrigins` | `MCP_ALLOWED_ORIGINS` | empty |
| `mcp.authToken` | `MCP_AUTH_TOKEN` | empty on loopback |
| `mcp.oauth.enabled` | `MCP_OAUTH_ENABLED` | `false` |
| `mcp.oauth.resourceUri` | `MCP_OAUTH_RESOURCE_URI` | required when OAuth is enabled |
| `mcp.oauth.issuer` | `MCP_OAUTH_ISSUER` | required when OAuth is enabled |
| `mcp.oauth.requiredScopes` | `MCP_OAUTH_REQUIRED_SCOPES` | empty |
| `mcp.oauth.clockSkewSeconds` | `MCP_OAUTH_CLOCK_SKEW_SECONDS` | `30` |
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
OAuth scopes may be supplied as a comma- or space-separated string. The
configured resource URI is the RFC 8707 identifier that access tokens must
contain in their `aud` claim; it should be the externally visible MCP URL, even
when TLS terminates at a trusted reverse proxy.

### Local OAuth and TLS integration

The repository includes a production-style local reference stack with pinned
Keycloak and Caddy images. Keycloak imports a realm containing a pre-registered
confidential smoke-test client, a pre-registered public native client requiring
S256 PKCE, an optional `mcp:read` scope, and an audience mapper for the generated
public resource URI. Caddy terminates TLS with its local CA and proxies to the
OAuth-enabled shaded server on port 3001. The public TLS port defaults to
`18443` and can be changed with `-HttpsPort`.

With Docker running, execute:

```powershell
.\scripts\run-oauth-integration.ps1
```

The runner packages the server, starts the stack, obtains real scoped and
unscoped client-credentials tokens, completes an authorization-code flow with
S256 PKCE, validates state and the RFC 9207 issuer response parameter, and
verifies JWT claims. It checks RFC 9728 metadata, authenticated MCP discovery,
401/403 challenges, then runs official MCP Inspector `2.2.0` `tools/list` with
the PKCE token through TLS. All components are stopped afterward and detailed
evidence is retained under `logs/`; generated realm/CA state is ignored under
`.oauth-runtime/`.

The realm’s committed passwords and client secret are test fixtures only. Do
not reuse them or the development-mode Keycloak configuration in any deployed
environment. Production deployments must replace the fixture provider and
pre-registration with their selected authorization service and client strategy.

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
70% branches. Run `./scripts/verify-packaging.ps1` to prove consecutive package
passes are idempotent and that the executable classpath JAR has the expected
main class without dependency module descriptors.
