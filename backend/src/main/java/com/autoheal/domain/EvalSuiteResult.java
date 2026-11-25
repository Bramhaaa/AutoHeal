package com.autoheal.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record EvalSuiteResult(
    EvalConfig config,
    List<EvalCaseOutcome> outcomes,
    double fixRateAt1,
    double fixRateAtMax,
    double meanLogReductionPercent,
    long medianDurationMs,
    long p90DurationMs,
    BigDecimal meanCostUsd,
    Map<String, Integer> failureTaxonomy) {

  public EvalSuiteResult {
    Objects.requireNonNull(config, "config must not be null");
    outcomes = List.copyOf(Objects.requireNonNull(outcomes, "outcomes must not be null"));
    meanCostUsd = meanCostUsd == null ? BigDecimal.ZERO : meanCostUsd;
    failureTaxonomy =
        Map.copyOf(Objects.requireNonNull(failureTaxonomy, "failureTaxonomy must not be null"));
  }
}
