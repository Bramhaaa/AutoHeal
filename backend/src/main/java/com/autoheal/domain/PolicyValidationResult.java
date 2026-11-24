package com.autoheal.domain;

import java.util.List;
import java.util.Objects;

public record PolicyValidationResult(boolean allowed, List<String> violations) {

  public PolicyValidationResult {
    violations = List.copyOf(Objects.requireNonNull(violations, "violations must not be null"));
  }

  public static PolicyValidationResult ok() {
    return new PolicyValidationResult(true, List.of());
  }

  public static PolicyValidationResult rejected(List<String> violations) {
    return new PolicyValidationResult(false, violations);
  }
}
