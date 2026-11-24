package com.autoheal.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record IncidentAttempt(
    UUID runId,
    int attemptNo,
    AttemptOutcome outcome,
    String diff,
    int sandboxExitCode,
    int testsRun,
    int testsFailed,
    long sandboxDurationMs,
    Instant createdAt) {

  public IncidentAttempt {
    Objects.requireNonNull(runId, "runId must not be null");
    Objects.requireNonNull(outcome, "outcome must not be null");
    Objects.requireNonNull(createdAt, "createdAt must not be null");
  }
}
