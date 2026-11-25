package com.autoheal.domain;

import java.time.Instant;
import java.util.Objects;

public record BenchmarkReport(
    Long id, String model, String configJson, String resultsJson, Instant createdAt) {

  public BenchmarkReport {
    Objects.requireNonNull(model, "model must not be null");
    Objects.requireNonNull(configJson, "configJson must not be null");
    Objects.requireNonNull(resultsJson, "resultsJson must not be null");
    Objects.requireNonNull(createdAt, "createdAt must not be null");
  }
}
