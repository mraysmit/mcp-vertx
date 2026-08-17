package dev.mars.a2a;

import dev.mars.mcp.testing.TestLoggingExtension;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.AgentSkill;
import org.a2aproject.sdk.spec.EventKind;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.TextPart;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith({VertxExtension.class, TestLoggingExtension.class})
class A2aServerVerticleTest {

  @Test
  void returns_standard_error_details_for_invalid_and_failed_send_operations(
      Vertx vertx, VertxTestContext context) {
    A2aAgent agent = new TestAgent() {
      @Override public Future<EventKind> sendMessage(MessageSendParams params) {
        return Future.failedFuture("domain failure");
      }
    };
    A2aServerVerticle server = new A2aServerVerticle(agent);
    HttpClient client = vertx.createHttpClient();
    JsonObject validBody = new JsonObject().put("message", new JsonObject()
        .put("role", "ROLE_USER")
        .put("parts", new io.vertx.core.json.JsonArray()
            .add(new JsonObject().put("text", "Hello")))
        .put("messageId", "request-failure"));

    vertx.deployVerticle(server, new DeploymentOptions().setConfig(new JsonObject()
            .put("a2a.port", 0)))
        .compose(ignored -> client.request(HttpMethod.POST, server.actualPort(),
            "127.0.0.1", "/a2a/message:send"))
        .compose(request -> request.putHeader("A2A-Version", "1.0")
            .putHeader("Content-Type", "application/a2a+json")
            .send(Buffer.buffer("{")))
        .compose(invalid -> invalid.body().map(body -> {
          assertEquals(400, invalid.statusCode());
          JsonObject detail = body.toJsonObject().getJsonObject("error")
              .getJsonArray("details").getJsonObject(0);
          assertEquals("INVALID_PARAMS", detail.getString("reason"));
          return null;
        }))
        .compose(ignored -> client.request(HttpMethod.POST, server.actualPort(),
            "127.0.0.1", "/a2a/message:send"))
        .compose(request -> request.putHeader("A2A-Version", "1.0")
            .putHeader("Content-Type", "application/a2a+json")
            .send(Buffer.buffer(validBody.encode())))
        .compose(failed -> failed.body().map(body -> {
          assertEquals(500, failed.statusCode());
          JsonObject detail = body.toJsonObject().getJsonObject("error")
              .getJsonArray("details").getJsonObject(0);
          assertEquals("INTERNAL_ERROR", detail.getString("reason"));
          return null;
        }))
        .onSuccess(ignored -> context.completeNow())
        .onFailure(context::failNow);
  }

  @Test
  void returns_standard_errors_for_missing_tasks_and_invalid_list_queries(
      Vertx vertx, VertxTestContext context) {
    A2aServerVerticle server = new A2aServerVerticle(new TestAgent());
    HttpClient client = vertx.createHttpClient();

    vertx.deployVerticle(server, new DeploymentOptions().setConfig(new JsonObject()
            .put("a2a.port", 0)))
        .compose(ignored -> client.request(HttpMethod.GET, server.actualPort(),
            "127.0.0.1", "/a2a/tasks/missing"))
        .compose(request -> request.putHeader("A2A-Version", "1.0").send())
        .compose(missing -> missing.body().map(body -> {
          assertEquals(404, missing.statusCode());
          assertEquals("TASK_NOT_FOUND", body.toJsonObject().getJsonObject("error")
              .getJsonArray("details").getJsonObject(0).getString("reason"));
          return null;
        }))
        .compose(ignored -> client.request(HttpMethod.GET, server.actualPort(),
            "127.0.0.1", "/a2a/tasks?pageSize=0"))
        .compose(request -> request.putHeader("A2A-Version", "1.0").send())
        .compose(invalid -> invalid.body().map(body -> {
          assertEquals(400, invalid.statusCode());
          assertEquals("INVALID_PARAMS", body.toJsonObject().getJsonObject("error")
              .getJsonArray("details").getJsonObject(0).getString("reason"));
          return null;
        }))
        .onSuccess(ignored -> context.completeNow())
        .onFailure(context::failNow);
  }

  @Test
  void rejects_non_loopback_binding_without_authentication(
      Vertx vertx, VertxTestContext context) {
    A2aServerVerticle server = new A2aServerVerticle(new TestAgent());

    vertx.deployVerticle(server, new DeploymentOptions().setConfig(new JsonObject()
            .put("a2a.host", "0.0.0.0")
            .put("a2a.port", 0)))
        .onSuccess(ignored -> context.failNow("insecure public binding was accepted"))
        .onFailure(error -> context.verify(() -> {
          assertTrue(error.getMessage().contains("a2a.authToken"));
          context.completeNow();
        }));
  }

  @Test
  void leaves_discovery_public_but_requires_the_configured_bearer_token_for_operations(
      Vertx vertx, VertxTestContext context) {
    AtomicBoolean invoked = new AtomicBoolean();
    A2aAgent agent = new TestAgent() {
      @Override public Future<EventKind> sendMessage(MessageSendParams params) {
        invoked.set(true);
        return Future.succeededFuture(Message.builder()
            .role(Message.Role.ROLE_AGENT)
            .parts(new TextPart("authorized"))
            .messageId("reply-authorized")
            .build());
      }
    };
    A2aServerVerticle server = new A2aServerVerticle(agent);
    JsonObject requestBody = new JsonObject().put("message", new JsonObject()
        .put("role", "ROLE_USER")
        .put("parts", new io.vertx.core.json.JsonArray()
            .add(new JsonObject().put("text", "Hello")))
        .put("messageId", "request-auth"));
    HttpClient client = vertx.createHttpClient();

    vertx.deployVerticle(server, new DeploymentOptions().setConfig(new JsonObject()
            .put("a2a.port", 0)
            .put("a2a.authToken", "secret")))
        .compose(ignored -> client.request(HttpMethod.GET, server.actualPort(),
            "127.0.0.1", "/.well-known/agent-card.json"))
        .compose(request -> request.send())
        .compose(discovery -> {
          assertEquals(200, discovery.statusCode());
          return client.request(HttpMethod.POST, server.actualPort(), "127.0.0.1",
              "/a2a/message:send");
        })
        .compose(request -> request.putHeader("A2A-Version", "1.0")
            .putHeader("Content-Type", "application/a2a+json")
            .send(Buffer.buffer(requestBody.encode())))
        .compose(unauthorized -> {
          assertEquals(401, unauthorized.statusCode());
          assertEquals("Bearer", unauthorized.getHeader("WWW-Authenticate"));
          assertTrue(!invoked.get());
          return client.request(HttpMethod.POST, server.actualPort(), "127.0.0.1",
              "/a2a/message:send");
        })
        .compose(request -> request.putHeader("A2A-Version", "1.0")
            .putHeader("Content-Type", "application/a2a+json")
            .putHeader("Authorization", "Bearer secret")
            .send(Buffer.buffer(requestBody.encode())))
        .onSuccess(authorized -> context.verify(() -> {
          assertEquals(200, authorized.statusCode());
          assertTrue(invoked.get());
          context.completeNow();
        }))
        .onFailure(context::failNow);
  }

  @Test
  void subscribes_with_a_task_snapshot_then_live_updates_until_terminal(
      Vertx vertx, VertxTestContext context) {
    InMemoryA2aTaskStore store = new InMemoryA2aTaskStore();
    store.save(Task.builder().id("task-subscribe").contextId("context-subscribe")
        .status(new TaskStatus(TaskState.TASK_STATE_WORKING)).build());
    A2aAgent agent = new A2aAgent() {
      @Override public AgentCard agentCard() { return testCard(true); }
      @Override public Future<EventKind> sendMessage(MessageSendParams params) {
        return Future.failedFuture("not used");
      }
    };
    A2aServerVerticle server = new A2aServerVerticle(agent, store);

    vertx.deployVerticle(server, new DeploymentOptions().setConfig(new JsonObject()
            .put("a2a.port", 0)))
        .compose(ignored -> vertx.createHttpClient().request(HttpMethod.GET,
            server.actualPort(), "127.0.0.1",
            "/a2a/tasks/task-subscribe:subscribe"))
        .compose(request -> request.putHeader("Accept", "text/event-stream")
            .putHeader("A2A-Version", "1.0").send())
        .compose(response -> {
          assertEquals(200, response.statusCode());
          vertx.setTimer(20, ignored -> store.apply(TaskStatusUpdateEvent.builder()
              .taskId("task-subscribe").contextId("context-subscribe")
              .status(new TaskStatus(TaskState.TASK_STATE_COMPLETED)).build()));
          return response.body();
        })
        .onSuccess(responseBody -> context.verify(() -> {
          String[] frames = responseBody.toString().trim().split("\\R\\R");
          assertEquals(2, frames.length);
          assertEquals("task-subscribe",
              new JsonObject(frames[0].substring("data: ".length()))
                  .getJsonObject("task").getString("id"));
          assertEquals("TASK_STATE_COMPLETED",
              new JsonObject(frames[1].substring("data: ".length()))
                  .getJsonObject("statusUpdate").getJsonObject("status")
                  .getString("state"));
          context.completeNow();
        }))
        .onFailure(context::failNow);
  }

  @Test
  void streams_ordered_task_events_over_sse_and_updates_the_task_store(
      Vertx vertx, VertxTestContext context) {
    InMemoryA2aTaskStore store = new InMemoryA2aTaskStore();
    A2aAgent agent = new A2aAgent() {
      @Override public AgentCard agentCard() { return testCard(); }
      @Override public Future<EventKind> sendMessage(MessageSendParams params) {
        return Future.failedFuture("streaming path expected");
      }
      @Override
      public Future<Void> streamMessage(MessageSendParams params, A2aEventEmitter emitter) {
        Task working = Task.builder().id("task-stream").contextId("context-stream")
            .status(new TaskStatus(TaskState.TASK_STATE_WORKING)).build();
        TaskStatusUpdateEvent completed = TaskStatusUpdateEvent.builder()
            .taskId("task-stream")
            .contextId("context-stream")
            .status(new TaskStatus(TaskState.TASK_STATE_COMPLETED))
            .build();
        return emitter.emit(working).compose(ignored -> emitter.emit(completed));
      }
    };
    A2aServerVerticle server = new A2aServerVerticle(agent, store);
    JsonObject body = new JsonObject().put("message", new JsonObject()
        .put("role", "ROLE_USER")
        .put("parts", new io.vertx.core.json.JsonArray()
            .add(new JsonObject().put("text", "Stream")))
        .put("messageId", "request-stream"));

    vertx.deployVerticle(server, new DeploymentOptions().setConfig(new JsonObject()
            .put("a2a.port", 0)))
        .compose(ignored -> vertx.createHttpClient().request(HttpMethod.POST,
            server.actualPort(), "127.0.0.1", "/a2a/message:stream"))
        .compose(request -> request.putHeader("Content-Type", "application/a2a+json")
            .putHeader("Accept", "text/event-stream")
            .putHeader("A2A-Version", "1.0").send(Buffer.buffer(body.encode())))
        .compose(response -> {
          assertEquals(200, response.statusCode());
          assertEquals("text/event-stream", response.getHeader("Content-Type"));
          return response.body();
        })
        .onSuccess(responseBody -> context.verify(() -> {
          String[] frames = responseBody.toString().trim().split("\\R\\R");
          assertEquals(2, frames.length);
          assertTrue(new JsonObject(frames[0].substring("data: ".length()))
              .containsKey("task"));
          assertEquals("TASK_STATE_COMPLETED",
              new JsonObject(frames[1].substring("data: ".length()))
                  .getJsonObject("statusUpdate").getJsonObject("status")
                  .getString("state"));
          assertEquals(TaskState.TASK_STATE_COMPLETED,
              store.get("task-stream").orElseThrow().status().state());
          context.completeNow();
        }))
        .onFailure(context::failNow);
  }

  @Test
  void lists_filtered_tasks_with_cursor_metadata(
      Vertx vertx, VertxTestContext context) {
    InMemoryA2aTaskStore store = new InMemoryA2aTaskStore();
    store.save(Task.builder().id("older").contextId("context-1")
        .status(new TaskStatus(TaskState.TASK_STATE_WORKING, null,
            OffsetDateTime.parse("2026-08-17T10:00:00Z"))).build());
    store.save(Task.builder().id("newer").contextId("context-1")
        .status(new TaskStatus(TaskState.TASK_STATE_COMPLETED, null,
            OffsetDateTime.parse("2026-08-17T12:00:00Z"))).build());
    A2aAgent agent = new A2aAgent() {
      @Override public AgentCard agentCard() { return testCard(); }
      @Override public Future<EventKind> sendMessage(MessageSendParams params) {
        return Future.failedFuture("not used");
      }
    };
    A2aServerVerticle server = new A2aServerVerticle(agent, store);

    vertx.deployVerticle(server, new DeploymentOptions().setConfig(new JsonObject()
            .put("a2a.port", 0)))
        .compose(ignored -> vertx.createHttpClient().request(HttpMethod.GET,
            server.actualPort(), "127.0.0.1",
            "/a2a/tasks?contextId=context-1&pageSize=1"))
        .compose(request -> request.putHeader("A2A-Version", "1.0").send())
        .compose(response -> {
          assertEquals(200, response.statusCode());
          return response.body();
        })
        .onSuccess(body -> context.verify(() -> {
          JsonObject result = body.toJsonObject();
          assertEquals(2, result.getInteger("totalSize"));
          assertEquals(1, result.getInteger("pageSize"));
          assertEquals("newer", result.getJsonArray("tasks").getJsonObject(0)
              .getString("id"));
          assertTrue(!result.getString("nextPageToken").isBlank());
          context.completeNow();
        }))
        .onFailure(context::failNow);
  }

  @Test
  void cancels_a_non_terminal_task_and_persists_the_terminal_snapshot(
      Vertx vertx, VertxTestContext context) {
    InMemoryA2aTaskStore store = new InMemoryA2aTaskStore();
    store.save(Task.builder().id("task-1").contextId("context-1")
        .status(new TaskStatus(TaskState.TASK_STATE_WORKING)).build());
    AtomicReference<Task> canceled = new AtomicReference<>();
    A2aAgent agent = new A2aAgent() {
      @Override public AgentCard agentCard() { return testCard(); }
      @Override public Future<EventKind> sendMessage(MessageSendParams params) {
        return Future.failedFuture("not used");
      }
      @Override public Future<Task> cancelTask(Task task) {
        canceled.set(task);
        return Future.succeededFuture(Task.builder(task)
            .status(new TaskStatus(TaskState.TASK_STATE_CANCELED)).build());
      }
    };
    A2aServerVerticle server = new A2aServerVerticle(agent, store);

    vertx.deployVerticle(server, new DeploymentOptions().setConfig(new JsonObject()
            .put("a2a.port", 0)))
        .compose(ignored -> vertx.createHttpClient().request(HttpMethod.POST,
            server.actualPort(), "127.0.0.1", "/a2a/tasks/task-1:cancel"))
        .compose(request -> request.putHeader("Content-Type", "application/a2a+json")
            .putHeader("A2A-Version", "1.0").send(Buffer.buffer("{}")))
        .compose(response -> {
          assertEquals(200, response.statusCode());
          return response.body();
        })
        .onSuccess(body -> context.verify(() -> {
          assertEquals("task-1", canceled.get().id());
          assertEquals("TASK_STATE_CANCELED", body.toJsonObject()
              .getJsonObject("status").getString("state"));
          assertEquals(TaskState.TASK_STATE_CANCELED,
              store.get("task-1").orElseThrow().status().state());
          context.completeNow();
        }))
        .onFailure(context::failNow);
  }

  @Test
  void persists_and_retrieves_tasks_returned_by_send_message(
      Vertx vertx, VertxTestContext context) {
    A2aAgent agent = new A2aAgent() {
      @Override public AgentCard agentCard() { return testCard(); }
      @Override public Future<EventKind> sendMessage(MessageSendParams params) {
        Task task = Task.builder()
            .id("task-1")
            .contextId("context-1")
            .status(new TaskStatus(TaskState.TASK_STATE_WORKING))
            .history(params.message())
            .build();
        return Future.succeededFuture(task);
      }
    };
    A2aServerVerticle server = new A2aServerVerticle(agent);
    JsonObject sendBody = new JsonObject().put("message", new JsonObject()
        .put("role", "ROLE_USER")
        .put("parts", new io.vertx.core.json.JsonArray()
            .add(new JsonObject().put("text", "Start work")))
        .put("messageId", "request-1"));
    HttpClient client = vertx.createHttpClient();

    vertx.deployVerticle(server, new DeploymentOptions().setConfig(new JsonObject()
            .put("a2a.port", 0)))
        .compose(ignored -> client.request(HttpMethod.POST, server.actualPort(),
            "127.0.0.1", "/a2a/message:send"))
        .compose(request -> request.putHeader("Content-Type", "application/a2a+json")
            .putHeader("A2A-Version", "1.0")
            .send(Buffer.buffer(sendBody.encode())))
        .compose(sendResponse -> {
          assertEquals(200, sendResponse.statusCode());
          return client.request(HttpMethod.GET, server.actualPort(), "127.0.0.1",
              "/a2a/tasks/task-1");
        })
        .compose(request -> request.putHeader("A2A-Version", "1.0").send())
        .compose(response -> {
          assertEquals(200, response.statusCode());
          assertEquals("application/a2a+json", response.getHeader("Content-Type"));
          return response.body();
        })
        .onSuccess(body -> context.verify(() -> {
          JsonObject task = body.toJsonObject();
          assertEquals("task-1", task.getString("id"));
          assertEquals("context-1", task.getString("contextId"));
          assertEquals("TASK_STATE_WORKING",
              task.getJsonObject("status").getString("state"));
          assertEquals("Start work", task.getJsonArray("history").getJsonObject(0)
              .getJsonArray("parts").getJsonObject(0).getString("text"));
          context.completeNow();
        }))
        .onFailure(context::failNow);
  }

  @Test
  void rejects_requests_that_do_not_select_the_supported_v1_protocol(
      Vertx vertx, VertxTestContext context) {
    AtomicBoolean invoked = new AtomicBoolean();
    A2aAgent agent = new A2aAgent() {
      @Override public AgentCard agentCard() { return testCard(); }
      @Override public Future<EventKind> sendMessage(MessageSendParams params) {
        invoked.set(true);
        return Future.failedFuture("must not execute");
      }
    };
    A2aServerVerticle server = new A2aServerVerticle(agent);
    JsonObject body = new JsonObject().put("message", new JsonObject()
        .put("role", "ROLE_USER")
        .put("parts", new io.vertx.core.json.JsonArray()
            .add(new JsonObject().put("text", "Hello")))
        .put("messageId", "request-1"));

    vertx.deployVerticle(server, new DeploymentOptions().setConfig(new JsonObject()
            .put("a2a.port", 0)))
        .compose(ignored -> vertx.createHttpClient().request(HttpMethod.POST,
            server.actualPort(), "127.0.0.1", "/a2a/message:send"))
        .compose(request -> request.putHeader("Content-Type", "application/a2a+json")
            .send(Buffer.buffer(body.encode())))
        .compose(response -> {
          assertEquals(400, response.statusCode());
          assertEquals("application/a2a+json", response.getHeader("Content-Type"));
          return response.body();
        })
        .onSuccess(responseBody -> context.verify(() -> {
          JsonObject error = responseBody.toJsonObject().getJsonObject("error");
          assertEquals("INVALID_ARGUMENT", error.getString("status"));
          JsonObject detail = error.getJsonArray("details").getJsonObject(0);
          assertEquals("VERSION_NOT_SUPPORTED", detail.getString("reason"));
          assertEquals("1.0", detail.getJsonObject("metadata")
              .getString("supportedVersions"));
          assertTrue(!invoked.get());
          context.completeNow();
        }))
        .onFailure(context::failNow);
  }

  @Test
  void sends_a_stateless_message_using_the_v1_http_json_binding(
      Vertx vertx, VertxTestContext context) {
    AtomicReference<MessageSendParams> received = new AtomicReference<>();
    A2aAgent agent = new A2aAgent() {
      @Override
      public AgentCard agentCard() {
        return testCard();
      }

      @Override
      public Future<EventKind> sendMessage(MessageSendParams params) {
        received.set(params);
        Message reply = Message.builder()
            .role(Message.Role.ROLE_AGENT)
            .parts(new TextPart("Hello from Vert.x"))
            .messageId("reply-1")
            .build();
        return Future.succeededFuture(reply);
      }
    };
    A2aServerVerticle server = new A2aServerVerticle(agent);
    JsonObject requestBody = new JsonObject().put("message", new JsonObject()
        .put("role", "ROLE_USER")
        .put("parts", new io.vertx.core.json.JsonArray()
            .add(new JsonObject().put("text", "Hello")))
        .put("messageId", "request-1"));

    vertx.deployVerticle(server, new DeploymentOptions().setConfig(new JsonObject()
            .put("a2a.port", 0)
            .put("a2a.host", "127.0.0.1")))
        .compose(ignored -> vertx.createHttpClient()
            .request(HttpMethod.POST, server.actualPort(), "127.0.0.1",
                "/a2a/message:send"))
        .compose(request -> request
            .putHeader("Content-Type", "application/a2a+json")
            .putHeader("A2A-Version", "1.0")
            .send(Buffer.buffer(requestBody.encode())))
        .compose(response -> {
          assertEquals(200, response.statusCode());
          assertEquals("application/a2a+json", response.getHeader("Content-Type"));
          return response.body();
        })
        .onSuccess(body -> context.verify(() -> {
          Message inbound = received.get().message();
          assertEquals(Message.Role.ROLE_USER, inbound.role());
          assertEquals("request-1", inbound.messageId());
          assertEquals("Hello", ((TextPart) inbound.parts().getFirst()).text());

          JsonObject response = body.toJsonObject();
          JsonObject message = response.getJsonObject("message");
          assertEquals("ROLE_AGENT", message.getString("role"));
          assertEquals("reply-1", message.getString("messageId"));
          assertEquals("Hello from Vert.x", message.getJsonArray("parts")
              .getJsonObject(0).getString("text"));
          context.completeNow();
        }))
        .onFailure(context::failNow);
  }

  @Test
  void discovers_the_v1_agent_card_on_an_independent_listener(
      Vertx vertx, VertxTestContext context) {
    A2aAgent agent = new A2aAgent() {
      @Override
      public AgentCard agentCard() {
        return testCard();
      }

      @Override
      public Future<EventKind> sendMessage(MessageSendParams params) {
        return Future.failedFuture("not used by discovery");
      }
    };
    A2aServerVerticle server = new A2aServerVerticle(agent);

    vertx.deployVerticle(server, new DeploymentOptions().setConfig(new JsonObject()
            .put("a2a.port", 0)
            .put("a2a.host", "127.0.0.1")))
        .compose(ignored -> {
          assertTrue(server.actualPort() > 0);
          HttpClient client = vertx.createHttpClient();
          return client.request(HttpMethod.GET, server.actualPort(), "127.0.0.1",
                  "/.well-known/agent-card.json")
              .compose(request -> request.send())
              .compose(response -> {
                assertEquals(200, response.statusCode());
                assertEquals("application/json", response.getHeader("Content-Type"));
                return response.body();
              });
        })
        .onSuccess(body -> context.verify(() -> {
          JsonObject card = body.toJsonObject();
          assertEquals("Vert.x test agent", card.getString("name"));
          assertEquals("1.0.0", card.getString("version"));
          assertEquals("HTTP+JSON", card.getJsonArray("supportedInterfaces")
              .getJsonObject(0).getString("protocolBinding"));
          assertEquals("1.0", card.getJsonArray("supportedInterfaces")
              .getJsonObject(0).getString("protocolVersion"));
          assertEquals("echo", card.getJsonArray("skills").getJsonObject(0).getString("id"));
          context.completeNow();
        }))
        .onFailure(context::failNow);
  }

  private AgentCard testCard() {
    return testCard(false);
  }

  private AgentCard testCard(boolean streaming) {
    AgentSkill echo = AgentSkill.builder()
        .id("echo")
        .name("Echo")
        .description("Echoes text")
        .tags(List.of("test"))
        .build();
    return AgentCard.builder()
        .name("Vert.x test agent")
        .description("A protocol fixture")
        .version("1.0.0")
        .capabilities(AgentCapabilities.builder().streaming(streaming).build())
        .defaultInputModes(List.of("text/plain"))
        .defaultOutputModes(List.of("text/plain"))
        .skills(List.of(echo))
        .supportedInterfaces(List.of(new AgentInterface(
            "HTTP+JSON", "http://127.0.0.1:3002/a2a", null, "1.0")))
        .build();
  }

  private class TestAgent implements A2aAgent {
    @Override public AgentCard agentCard() { return testCard(); }
    @Override public Future<EventKind> sendMessage(MessageSendParams params) {
      return Future.failedFuture("not used");
    }
  }
}
