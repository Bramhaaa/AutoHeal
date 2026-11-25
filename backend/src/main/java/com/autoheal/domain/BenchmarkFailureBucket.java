package com.autoheal.domain;

import java.util.Objects;

public record BenchmarkFailureBucket(String reason, int count) {

  public BenchmarkFailureBucket {
    Objects.requireNonNull(reason, "reason must not be null");
  }
}
