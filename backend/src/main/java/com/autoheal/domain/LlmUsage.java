package com.autoheal.domain;

import java.math.BigDecimal;
import java.util.Objects;

public record LlmUsage(
    String model,
    int promptTokens,
    int completionTokens,
    BigDecimal estimatedCostUsd,
    long latencyMs) {

  public LlmUsage {
    Objects.requireNonNull(model, "model must not be null");
    Objects.requireNonNull(estimatedCostUsd, "estimatedCostUsd must not be null");
  }
}
