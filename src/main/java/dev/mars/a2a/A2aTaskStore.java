package dev.mars.a2a;

import io.vertx.core.Future;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.ListTasksParams;
import org.a2aproject.sdk.spec.StreamingEventKind;

import java.util.Optional;

/** Persistence boundary for immutable A2A task snapshots. */
public interface A2aTaskStore {

  void save(Task task);

  Optional<Task> get(String taskId);

  A2aTaskPage list(ListTasksParams params);

  Future<Void> apply(StreamingEventKind event);

  A2aTaskSubscription subscribe(String taskId, A2aEventEmitter emitter);
}
