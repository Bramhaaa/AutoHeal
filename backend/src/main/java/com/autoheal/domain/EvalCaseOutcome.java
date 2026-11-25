package com.autoheal.domain;

import java.math.BigDecimal;
import java.util.Objects;

public record EvalCaseOutcome(
    String bugId,
    String category,
    boolean verified,
    int attemptsUsed,
    String failureReason,
    double logReductionPercent,
    long durationMs,
    BigDecimal costUsd) {

  public EvalCaseOutcome {
    Objects.requireNonNull(bugId, "bugId must not be null");
    Objects.requireNonNull(category, "category must not be null");
    costUsd = costUsd == null ? BigDecimal.ZERO : costUsd;
  }
}
