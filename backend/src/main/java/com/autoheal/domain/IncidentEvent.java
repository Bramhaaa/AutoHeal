package com.autoheal.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record IncidentEvent(
    UUID runId,
    int eventSeq,
    IncidentStage stage,
    IncidentEventType eventType,
    String payloadJson,
    Instant createdAt) {

  public IncidentEvent {
    Objects.requireNonNull(runId, "runId must not be null");
    Objects.requireNonNull(stage, "stage must not be null");
    Objects.requireNonNull(eventType, "eventType must not be null");
    Objects.requireNonNull(payloadJson, "payloadJson must not be null");
    Objects.requireNonNull(createdAt, "createdAt must not be null");
  }
}
