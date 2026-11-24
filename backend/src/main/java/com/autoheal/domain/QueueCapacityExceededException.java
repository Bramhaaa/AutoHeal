package com.autoheal.domain;

public class QueueCapacityExceededException extends RuntimeException {

  public QueueCapacityExceededException(int maxQueuedRuns) {
    super("Incident queue is at capacity (" + maxQueuedRuns + "). Try REPLAY mode.");
  }
}
