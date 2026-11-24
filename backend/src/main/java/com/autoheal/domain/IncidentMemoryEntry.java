package com.autoheal.domain;

import java.util.Objects;
import java.util.UUID;

public record IncidentMemoryEntry(
    UUID id,
    IncidentMemorySource source,
    String originBugId,
    String errorSignature,
    String resolutionSummary,
    double similarityScore) {

  public IncidentMemoryEntry {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(source, "source must not be null");
    Objects.requireNonNull(errorSignature, "errorSignature must not be null");
    Objects.requireNonNull(resolutionSummary, "resolutionSummary must not be null");
    if (errorSignature.isBlank()) {
      throw new IllegalArgumentException("errorSignature must not be blank");
    }
    if (resolutionSummary.isBlank()) {
      throw new IllegalArgumentException("resolutionSummary must not be blank");
    }
  }
}
