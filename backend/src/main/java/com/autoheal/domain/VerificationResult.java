package com.autoheal.domain;

import java.util.List;
import java.util.Objects;

public record VerificationResult(boolean verified, List<String> reasons) {

  public VerificationResult {
    reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons must not be null"));
  }

  public static VerificationResult ok() {
    return new VerificationResult(true, List.of());
  }

  public static VerificationResult rejected(List<String> reasons) {
    return new VerificationResult(false, reasons);
  }
}
