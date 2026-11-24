package com.autoheal.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IncidentRepository {

  IncidentRun createRun(IncidentRun run);

  Optional<IncidentRun> findRunById(UUID runId);

  void updateRunStatus(UUID runId, IncidentStatus status);

  void completeRun(
      UUID runId,
      IncidentStatus status,
      String failureReason,
      int attemptsUsed,
      String rootCauseSummary,
      String generatedDiff,
      String prUrl,
      int totalPromptTokens,
      int totalCompletionTokens,
      BigDecimal estimatedCostUsd,
      long durationMs,
      Instant completedAt);

  void saveAttempt(IncidentAttempt attempt);

  List<IncidentAttempt> findAttemptsByRunId(UUID runId);

  void saveLlmCall(LlmCallRecord call);

  BigDecimal sumDailyLlmCostUsd(Instant dayStartInclusive, Instant dayEndExclusive);

  IncidentEvent appendEvent(
      UUID runId, IncidentStage stage, IncidentEventType eventType, String payloadJson);

  List<IncidentEvent> findEventsAfter(UUID runId, int afterEventSeq);
}
