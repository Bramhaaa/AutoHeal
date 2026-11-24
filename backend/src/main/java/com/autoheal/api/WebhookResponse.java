package com.autoheal.api;

import com.autoheal.domain.WebhookProcessingResult;
import java.util.UUID;

public record WebhookResponse(String status, UUID runId, String streamUrl, String message) {

  public static WebhookResponse from(WebhookProcessingResult result) {
    String streamUrl =
        result.runId() == null ? null : "/api/v1/incidents/" + result.runId() + "/stream";
    return new WebhookResponse(result.status().name(), result.runId(), streamUrl, result.message());
  }
}
