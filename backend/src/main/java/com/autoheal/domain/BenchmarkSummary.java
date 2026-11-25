package com.autoheal.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record BenchmarkSummary(
    String reportId,
    Instant generatedAt,
    int totalBugs,
    double passAt1Rate,
    double passAt3Rate,
    long medianDurationMs,
    long p90DurationMs,
    BigDecimal meanCostUsd,
    List<BenchmarkAblationRow> ablations,
    List<BenchmarkFailureBucket> failureTaxonomy) {

  public BenchmarkSummary {
    meanCostUsd = meanCostUsd == null ? BigDecimal.ZERO : meanCostUsd;
    ablations = ablations == null ? List.of() : List.copyOf(ablations);
    failureTaxonomy = failureTaxonomy == null ? List.of() : List.copyOf(failureTaxonomy);
  }
}
