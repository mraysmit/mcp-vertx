# mcp-vertx

A standalone Model Context Protocol server implemented in Java 25 with Vert.x 5.
The project contains only the MCP transport, session handling, JSON-RPC dispatch,
tool API, launcher, and tests. It has no agent, LLM, workflow, or domain-specific
application dependencies.

## Features

- Streamable HTTP transport at `/mcp` for protocol version `2025-03-26`
- Legacy HTTP+SSE compatibility at `/sse` and `/message`
- JSON-RPC `initialize`, `ping`, `tools/list`, and `tools/call`
- MCP session creation, validation, and termination
- Single and batch JSON-RPC requests
- Programmatic and `ServiceLoader`-based tool registration
- Runnable shaded JAR

## Build and test

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-25'
mvn clean verify
```

## Run

```powershell
java -jar target/mcp-vertx-0.2.0-SNAPSHOT.jar
```

The server starts on port `3001` and exposes `/mcp`. With no external tool
providers on the classpath, `tools/list` returns an empty list.

Configuration can be supplied using system properties or environment variables:

| System property | Environment variable | Default |
| --- | --- | --- |
| `mcp.port` | `MCP_PORT` | `3001` |
| `mcp.basePath` | `MCP_BASE_PATH` | empty |
| `mcp.resourceIdField` | `MCP_RESOURCE_ID_FIELD` | `resourceId` |

For example:

```powershell
$env:MCP_PORT = '8080'
$env:MCP_BASE_PATH = '/api'
java -jar target/mcp-vertx-0.2.0-SNAPSHOT.jar
```

## Register tools

Implement `dev.mars.mcp.tool.Tool` in a class with a public no-argument
constructor:

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
            .put("message", new JsonObject().put("type", "string")));
  }

  public Future<JsonObject> invoke(JsonObject arguments, ToolContext context) {
    return Future.succeededFuture(
        new JsonObject().put("message", arguments.getString("message")));
  }
}
```

For the standalone launcher, list the implementation class in:

```text
META-INF/services/dev.mars.mcp.tool.Tool
```

Add the provider JAR as a Maven dependency before packaging so the shaded JAR
includes it, or launch both JARs on the classpath:

```powershell
java -cp "target/mcp-vertx-0.2.0-SNAPSHOT.jar;path\to\provider.jar" dev.mars.mcp.Main
```

Alternatively, embed the verticle and register tools directly:

```java
var tools = ToolRegistry.of(new EchoTool());
vertx.deployVerticle(new McpServerVerticle(tools));
```

## Project layout

```text
src/main/java/dev/mars/mcp/
├── Main.java
├── McpServerVerticle.java
└── tool/
    ├── Tool.java
    ├── ToolContext.java
    └── ToolRegistry.java
```

See `mcp-vertx.http` for an initialization and request sequence.
