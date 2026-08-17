package dev.mars.a2a;

import io.vertx.core.Future;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.EventKind;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.StreamingEventKind;

/**
 * Vert.x-native application boundary for one remotely addressable A2A agent.
 *
 * <p>The protocol records come from the official A2A Java SDK while asynchronous
 * execution remains expressed as a Vert.x {@link Future}. Transport concerns are
 * owned by {@link A2aServerVerticle} and do not leak into agent implementations.
 */
public interface A2aAgent {

  AgentCard agentCard();

  Future<EventKind> sendMessage(MessageSendParams params);

  default Future<Void> streamMessage(MessageSendParams params, A2aEventEmitter emitter) {
    return sendMessage(params).compose(event -> {
      if (!(event instanceof StreamingEventKind streamingEvent)) {
        return Future.failedFuture(
            "SendMessage returned an event that cannot be streamed");
      }
      return emitter.emit(streamingEvent);
    });
  }

  default Future<Task> cancelTask(Task task) {
    return Future.failedFuture(new UnsupportedOperationException(
        "This agent does not support task cancellation"));
  }
}
