package com.autoheal.domain;

import java.util.List;
import java.util.Objects;

public record AppliedPatch(
    List<String> modifiedFiles, List<String> createdFiles, String unifiedDiff) {

  public AppliedPatch {
    modifiedFiles =
        List.copyOf(Objects.requireNonNull(modifiedFiles, "modifiedFiles must not be null"));
    createdFiles =
        List.copyOf(Objects.requireNonNull(createdFiles, "createdFiles must not be null"));
    Objects.requireNonNull(unifiedDiff, "unifiedDiff must not be null");
  }
}
