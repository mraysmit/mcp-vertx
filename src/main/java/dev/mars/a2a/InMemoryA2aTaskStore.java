package dev.mars.a2a;

import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.ListTasksParams;
import org.a2aproject.sdk.spec.StreamingEventKind;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import io.vertx.core.Future;

/** Thread-safe process-local task storage intended for standalone deployments. */
public final class InMemoryA2aTaskStore implements A2aTaskStore {

  private final ConcurrentMap<String, StoredTask> tasks = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, CopyOnWriteArrayList<A2aEventEmitter>> subscribers =
      new ConcurrentHashMap<>();

  @Override
  public void save(Task task) {
    Objects.requireNonNull(task, "task");
    if (task.id() == null || task.id().isBlank()) {
      throw new IllegalArgumentException("Task id must not be blank");
    }
    if (task.status() == null || task.status().state() == null) {
      throw new IllegalArgumentException("Task status and state are required");
    }
    tasks.compute(task.id(), (id, current) -> {
      if (current != null && current.task().status().state().isFinal()
          && !current.task().equals(task)) {
        throw new IllegalStateException(
            "Task " + id + " is terminal and cannot be changed");
      }
      if (current != null && current.task().equals(task)) return current;
      Instant updatedAt = task.status().timestamp() == null
          ? Instant.now() : task.status().timestamp().toInstant();
      return new StoredTask(task, updatedAt);
    });
  }

  @Override
  public Optional<Task> get(String taskId) {
    if (taskId == null || taskId.isBlank()) return Optional.empty();
    return Optional.ofNullable(tasks.get(taskId)).map(StoredTask::task);
  }

  @Override
  public A2aTaskPage list(ListTasksParams params) {
    Objects.requireNonNull(params, "params");
    List<StoredTask> matches = tasks.values().stream()
        .filter(item -> params.contextId() == null
            || params.contextId().equals(item.task().contextId()))
        .filter(item -> params.status() == null
            || params.status() == item.task().status().state())
        .filter(item -> params.statusTimestampAfter() == null
            || item.updatedAt().isAfter(params.statusTimestampAfter()))
        .sorted(Comparator.comparing(StoredTask::updatedAt).reversed()
            .thenComparing(item -> item.task().id()))
        .toList();

    int start = cursorStart(matches, params.pageToken());
    int requestedSize = params.getEffectivePageSize();
    int end = Math.min(start + requestedSize, matches.size());
    List<Task> page = new ArrayList<>(Math.max(0, end - start));
    for (int index = start; index < end; index++) {
      page.add(project(matches.get(index).task(), params));
    }
    String next = end < matches.size() ? encodeCursor(matches.get(end - 1)) : "";
    return new A2aTaskPage(page, matches.size(), page.size(), next);
  }

  @Override
  public Future<Void> apply(StreamingEventKind event) {
    Objects.requireNonNull(event, "event");
    if (event instanceof Task task) {
      save(task);
    } else if (event instanceof TaskStatusUpdateEvent update) {
      Task current = get(update.taskId()).orElseThrow(() ->
          new IllegalStateException("Task " + update.taskId() + " was not initialized"));
      verifyContext(current, update.contextId());
      save(Task.builder(current).status(update.status()).build());
    } else if (event instanceof TaskArtifactUpdateEvent update) {
      Task current = get(update.taskId()).orElseThrow(() ->
          new IllegalStateException("Task " + update.taskId() + " was not initialized"));
      verifyContext(current, update.contextId());
      List<org.a2aproject.sdk.spec.Artifact> artifacts = current.artifacts() == null
          ? new ArrayList<>() : new ArrayList<>(current.artifacts());
      int existingIndex = -1;
      for (int index = 0; index < artifacts.size(); index++) {
        if (artifacts.get(index).artifactId().equals(update.artifact().artifactId())) {
          existingIndex = index;
          break;
        }
      }
      org.a2aproject.sdk.spec.Artifact next = update.artifact();
      if (Boolean.TRUE.equals(update.append()) && existingIndex >= 0) {
        org.a2aproject.sdk.spec.Artifact existing = artifacts.get(existingIndex);
        List<org.a2aproject.sdk.spec.Part<?>> parts = new ArrayList<>();
        if (existing.parts() != null) parts.addAll(existing.parts());
        if (update.artifact().parts() != null) parts.addAll(update.artifact().parts());
        next = org.a2aproject.sdk.spec.Artifact.builder(existing)
            .parts(parts)
            .build();
      }
      if (existingIndex >= 0) artifacts.set(existingIndex, next);
      else artifacts.add(next);
      save(Task.builder(current).artifacts(artifacts).build());
    }

    String taskId = taskId(event);
    Future<Void> delivered = Future.succeededFuture();
    for (A2aEventEmitter subscriber : subscribers.getOrDefault(
        taskId, new CopyOnWriteArrayList<>())) {
      delivered = delivered.compose(ignored -> subscriber.emit(event));
    }
    return delivered;
  }

  @Override
  public A2aTaskSubscription subscribe(String taskId, A2aEventEmitter emitter) {
    Objects.requireNonNull(emitter, "emitter");
    Task snapshot = get(taskId).orElseThrow(() ->
        new IllegalArgumentException("Task " + taskId + " was not found"));
    CopyOnWriteArrayList<A2aEventEmitter> listeners = subscribers.computeIfAbsent(
        taskId, ignored -> new CopyOnWriteArrayList<>());
    listeners.add(emitter);
    return new A2aTaskSubscription() {
      private boolean closed;
      @Override public Task snapshot() { return snapshot; }
      @Override public void close() {
        if (closed) return;
        closed = true;
        listeners.remove(emitter);
        if (listeners.isEmpty()) subscribers.remove(taskId, listeners);
      }
    };
  }

  private String taskId(StreamingEventKind event) {
    if (event instanceof Task task) return task.id();
    if (event instanceof TaskStatusUpdateEvent update) return update.taskId();
    if (event instanceof TaskArtifactUpdateEvent update) return update.taskId();
    return "";
  }

  private void verifyContext(Task task, String contextId) {
    if (contextId != null && !contextId.equals(task.contextId())) {
      throw new IllegalArgumentException(
          "Task " + task.id() + " context does not match the update");
    }
  }

  private int cursorStart(List<StoredTask> matches, String token) {
    if (token == null || token.isBlank()) return 0;
    String decoded;
    try {
      decoded = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException("Invalid A2A task page token", error);
    }
    for (int index = 0; index < matches.size(); index++) {
      if (cursorValue(matches.get(index)).equals(decoded)) return index + 1;
    }
    throw new IllegalArgumentException("A2A task page token is stale or invalid");
  }

  private String encodeCursor(StoredTask task) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(
        cursorValue(task).getBytes(StandardCharsets.UTF_8));
  }

  private String cursorValue(StoredTask task) {
    return task.updatedAt() + "\n" + task.task().id();
  }

  private Task project(Task task, ListTasksParams params) {
    Task.Builder builder = Task.builder(task);
    if (!params.shouldIncludeArtifacts()) builder.artifacts(null);
    List<org.a2aproject.sdk.spec.Message> history = task.history();
    int historyLength = params.getEffectiveHistoryLength();
    if (history == null || historyLength == 0) {
      builder.history((List<org.a2aproject.sdk.spec.Message>) null);
    } else if (history.size() > historyLength) {
      builder.history(history.subList(history.size() - historyLength, history.size()));
    }
    return builder.build();
  }

  private record StoredTask(Task task, Instant updatedAt) {}
}
