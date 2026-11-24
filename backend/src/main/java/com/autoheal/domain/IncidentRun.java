package com.autoheal.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record IncidentRun(
    UUID id,
    IncidentSource source,
    String scenarioId,
    String repoName,
    String commitSha,
    IncidentStatus status,
    String failureReason,
    int attemptsUsed,
    boolean ragEnabled,
    String rootCauseSummary,
    String generatedDiff,
    String prUrl,
    int totalPromptTokens,
    int totalCompletionTokens,
    BigDecimal estimatedCostUsd,
    Long durationMs,
    Instant createdAt,
    Instant completedAt) {

  public IncidentRun {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(source, "source must not be null");
    Objects.requireNonNull(repoName, "repoName must not be null");
    Objects.requireNonNull(commitSha, "commitSha must not be null");
    Objects.requireNonNull(status, "status must not be null");
    Objects.requireNonNull(estimatedCostUsd, "estimatedCostUsd must not be null");
    Objects.requireNonNull(createdAt, "createdAt must not be null");
  }

  public static IncidentRun queued(
      UUID id,
      IncidentSource source,
      String scenarioId,
      String repoName,
      String commitSha,
      boolean ragEnabled,
      Instant createdAt) {
    return new IncidentRun(
        id,
        source,
        scenarioId,
        repoName,
        commitSha,
        IncidentStatus.QUEUED,
        null,
        0,
        ragEnabled,
        null,
        null,
        null,
        0,
        0,
        BigDecimal.ZERO,
        null,
        createdAt,
        null);
  }
}
