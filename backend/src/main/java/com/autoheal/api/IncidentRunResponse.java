package com.autoheal.api;

import com.autoheal.domain.IncidentAttempt;
import com.autoheal.domain.IncidentRun;
import com.autoheal.domain.IncidentSource;
import com.autoheal.domain.IncidentStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record IncidentRunResponse(
    UUID runId,
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
    Instant completedAt,
    List<IncidentAttempt> attempts) {

  public static IncidentRunResponse from(IncidentRun run, List<IncidentAttempt> attempts) {
    return new IncidentRunResponse(
        run.id(),
        run.source(),
        run.scenarioId(),
        run.repoName(),
        run.commitSha(),
        run.status(),
        run.failureReason(),
        run.attemptsUsed(),
        run.ragEnabled(),
        run.rootCauseSummary(),
        run.generatedDiff(),
        run.prUrl(),
        run.totalPromptTokens(),
        run.totalCompletionTokens(),
        run.estimatedCostUsd(),
        run.durationMs(),
        run.createdAt(),
        run.completedAt(),
        List.copyOf(attempts));
  }
}
