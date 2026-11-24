package com.autoheal.domain;

import java.util.Objects;

public record AttemptRecord(
    int attemptNo,
    AttemptOutcome outcome,
    String unifiedDiff,
    int sandboxExitCode,
    int testsRun,
    int testsFailed,
    long sandboxDurationMs,
    String feedback) {

  public AttemptRecord {
    Objects.requireNonNull(outcome, "outcome must not be null");
    Objects.requireNonNull(unifiedDiff, "unifiedDiff must not be null");
    Objects.requireNonNull(feedback, "feedback must not be null");
  }
}
