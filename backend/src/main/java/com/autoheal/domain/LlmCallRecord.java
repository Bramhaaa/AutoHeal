package com.autoheal.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record LlmCallRecord(
    UUID runId,
    Integer attemptNo,
    String model,
    int promptTokens,
    int completionTokens,
    BigDecimal costUsd,
    long latencyMs,
    Instant createdAt) {

  public LlmCallRecord {
    Objects.requireNonNull(runId, "runId must not be null");
    Objects.requireNonNull(model, "model must not be null");
    Objects.requireNonNull(costUsd, "costUsd must not be null");
    Objects.requireNonNull(createdAt, "createdAt must not be null");
  }
}
