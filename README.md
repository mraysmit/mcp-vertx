# mcp-vertx

A current-generation Model Context Protocol server framework implemented in
Java 25 with Vert.x 5.

## Protocol support

- MCP protocol revision `2026-07-28`
- Stateless Streamable HTTP at `POST /mcp`
- Required `server/discover`, `ping`, `tools/list`, and `tools/call` methods
- Per-request MCP version, method, client identity, and capability validation
- JSON Schema 2020-12 and draft-07 tool-input validation
- Structured and text tool results with MCP `isError` handling

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

## Security defaults

- Loopback-only binding
- Every supplied `Origin` is rejected unless explicitly allow-listed
- A bearer token is mandatory when binding to a non-loopback interface
- Per-client request rate limiting
- Bounded request bodies, tool execution time, and tool-result size

To expose the server outside the local machine:

```powershell
$env:MCP_HOST = '0.0.0.0'
$env:MCP_AUTH_TOKEN = '<generate-a-long-random-token>'
java -jar target/mcp-vertx-0.3.0-SNAPSHOT.jar
```

Send the token as `Authorization: Bearer <token>`. Use TLS at a reverse proxy
when the endpoint crosses a trusted local network boundary.

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
| `mcp.maxToolResultBytes` | `MCP_MAX_TOOL_RESULT_BYTES` | `1048576` |

`mcp.allowedOrigins` is a comma-separated list of complete origins such as
`https://client.example,https://admin.example`. Wildcard origins are rejected.

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

See `mcp-vertx.http` for complete `server/discover`, `tools/list`, and
`tools/call` request examples with the required modern MCP headers and metadata.
