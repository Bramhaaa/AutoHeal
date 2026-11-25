package com.autoheal.domain;

import java.math.BigDecimal;
import java.util.Objects;

public record BenchmarkAblationRow(
    String label,
    double passAt1Rate,
    double passAt3Rate,
    long medianDurationMs,
    BigDecimal meanCostUsd) {

  public BenchmarkAblationRow {
    Objects.requireNonNull(label, "label must not be null");
    meanCostUsd = meanCostUsd == null ? BigDecimal.ZERO : meanCostUsd;
  }
}
