package dev.mars.a2a;

import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.ListTasksParams;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.Test;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InMemoryA2aTaskStoreTest {

  @Test
  void applies_incremental_artifact_updates_to_the_current_snapshot() {
    InMemoryA2aTaskStore store = new InMemoryA2aTaskStore();
    store.save(task("task-1", TaskState.TASK_STATE_WORKING));
    store.apply(TaskArtifactUpdateEvent.builder()
        .taskId("task-1").contextId("context-1")
        .artifact(Artifact.builder().artifactId("artifact-1")
            .parts(new TextPart("first")).build())
        .append(false).lastChunk(false).build());
    store.apply(TaskArtifactUpdateEvent.builder()
        .taskId("task-1").contextId("context-1")
        .artifact(Artifact.builder().artifactId("artifact-1")
            .parts(new TextPart("second")).build())
        .append(true).lastChunk(true).build());

    Artifact artifact = store.get("task-1").orElseThrow().artifacts().getFirst();
    assertEquals(java.util.List.of("first", "second"), artifact.parts().stream()
        .map(part -> ((TextPart) part).text()).toList());
  }

  @Test
  void filters_orders_and_cursor_paginates_task_snapshots() {
    InMemoryA2aTaskStore store = new InMemoryA2aTaskStore();
    store.save(task("task-1", "context-1", TaskState.TASK_STATE_WORKING, 10));
    store.save(task("task-2", "context-1", TaskState.TASK_STATE_COMPLETED, 12));
    store.save(task("task-3", "context-2", TaskState.TASK_STATE_WORKING, 11));

    A2aTaskPage first = store.list(ListTasksParams.builder()
        .contextId("context-1").pageSize(1).build());
    assertEquals(2, first.totalSize());
    assertEquals(1, first.pageSize());
    assertEquals("task-2", first.tasks().getFirst().id());

    A2aTaskPage second = store.list(ListTasksParams.builder()
        .contextId("context-1").pageSize(1).pageToken(first.nextPageToken()).build());
    assertEquals("task-1", second.tasks().getFirst().id());
    assertEquals("", second.nextPageToken());

    A2aTaskPage working = store.list(ListTasksParams.builder()
        .status(TaskState.TASK_STATE_WORKING).pageSize(10).build());
    assertEquals(java.util.List.of("task-3", "task-1"),
        working.tasks().stream().map(Task::id).toList());
  }

  @Test
  void persists_progress_but_keeps_terminal_tasks_immutable() {
    InMemoryA2aTaskStore store = new InMemoryA2aTaskStore();
    store.save(task("task-1", TaskState.TASK_STATE_WORKING));
    store.save(task("task-1", TaskState.TASK_STATE_COMPLETED));

    assertEquals(TaskState.TASK_STATE_COMPLETED,
        store.get("task-1").orElseThrow().status().state());
    IllegalStateException error = assertThrows(IllegalStateException.class,
        () -> store.save(task("task-1", TaskState.TASK_STATE_WORKING)));
    assertEquals("Task task-1 is terminal and cannot be changed", error.getMessage());
  }

  private Task task(String id, TaskState state) {
    return Task.builder()
        .id(id)
        .contextId("context-1")
        .status(new TaskStatus(state))
        .build();
  }

  private Task task(String id, String contextId, TaskState state, int hour) {
    return Task.builder()
        .id(id)
        .contextId(contextId)
        .status(new TaskStatus(state, null,
            OffsetDateTime.of(2026, 8, 17, hour, 0, 0, 0, ZoneOffset.UTC)))
        .build();
  }
}
