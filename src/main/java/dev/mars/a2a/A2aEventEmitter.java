package dev.mars.a2a;

import io.vertx.core.Future;
import org.a2aproject.sdk.spec.StreamingEventKind;

/** Backpressure-aware sink for one ordered A2A streaming response. */
@FunctionalInterface
public interface A2aEventEmitter {

  Future<Void> emit(StreamingEventKind event);
}
