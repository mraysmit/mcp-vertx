package dev.mars.a2a;

import org.a2aproject.sdk.spec.Task;

import java.util.List;

/** One stable cursor page of A2A task snapshots. */
public record A2aTaskPage(
    List<Task> tasks,
    int totalSize,
    int pageSize,
    String nextPageToken) {

  public A2aTaskPage {
    tasks = List.copyOf(tasks);
    nextPageToken = nextPageToken == null ? "" : nextPageToken;
  }
}
