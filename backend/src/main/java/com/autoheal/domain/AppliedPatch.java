package com.autoheal.domain;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record AppliedPatch(
    List<String> modifiedFiles,
    List<String> createdFiles,
    String unifiedDiff,
    Map<String, String> fileContents) {

  public AppliedPatch {
    modifiedFiles =
        List.copyOf(Objects.requireNonNull(modifiedFiles, "modifiedFiles must not be null"));
    createdFiles =
        List.copyOf(Objects.requireNonNull(createdFiles, "createdFiles must not be null"));
    Objects.requireNonNull(unifiedDiff, "unifiedDiff must not be null");
    fileContents =
        Map.copyOf(Objects.requireNonNull(fileContents, "fileContents must not be null"));
  }
}
