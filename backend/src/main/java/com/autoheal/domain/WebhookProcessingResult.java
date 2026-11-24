package com.autoheal.domain;

import java.util.Objects;
import java.util.UUID;

public record WebhookProcessingResult(Status status, UUID runId, String message) {

  public enum Status {
    ACCEPTED,
    IGNORED,
    DUPLICATE
  }

  public WebhookProcessingResult {
    Objects.requireNonNull(status, "status must not be null");
    Objects.requireNonNull(message, "message must not be null");
  }

  public static WebhookProcessingResult accepted(UUID runId) {
    Objects.requireNonNull(runId, "runId must not be null");
    return new WebhookProcessingResult(Status.ACCEPTED, runId, "Webhook failure queued");
  }

  public static WebhookProcessingResult ignored(String reason) {
    return new WebhookProcessingResult(Status.IGNORED, null, reason);
  }

  public static WebhookProcessingResult duplicate(String deliveryId) {
    return new WebhookProcessingResult(
        Status.DUPLICATE, null, "Duplicate delivery ignored: " + deliveryId);
  }
}
