package dev.mars.a2a;

import org.a2aproject.sdk.spec.Task;

/** Registered live task stream paired with its initial consistent snapshot. */
public interface A2aTaskSubscription extends AutoCloseable {

  Task snapshot();

  @Override
  void close();
}
