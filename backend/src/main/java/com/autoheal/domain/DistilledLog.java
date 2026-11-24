package com.autoheal.domain;

import java.util.List;
import java.util.Objects;

public record DistilledLog(
    String summary,
    List<String> failingTests,
    List<String> referencedFiles,
    int rawLength,
    int distilledLength) {

  public DistilledLog {
    Objects.requireNonNull(summary, "summary must not be null");
    failingTests =
        List.copyOf(Objects.requireNonNull(failingTests, "failingTests must not be null"));
    referencedFiles =
        List.copyOf(Objects.requireNonNull(referencedFiles, "referencedFiles must not be null"));
  }

  public double reductionPercent() {
    if (rawLength <= 0) {
      return 0.0;
    }
    return (1.0 - ((double) distilledLength / rawLength)) * 100.0;
  }
}
