package com.autoheal.domain;

import java.util.Objects;

public record EvalConfig(String label, boolean ragEnabled, int maxAttempts, boolean distillLogs) {

  public EvalConfig {
    Objects.requireNonNull(label, "label must not be null");
    if (maxAttempts < 1) {
      throw new IllegalArgumentException("maxAttempts must be >= 1");
    }
  }
}
