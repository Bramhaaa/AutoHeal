package com.autoheal.api;

import com.autoheal.domain.WebhookProcessingResult;
import com.autoheal.service.GitHubWebhookService;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/webhooks")
public class GitHubWebhookController {

  private final GitHubWebhookService webhookService;

  public GitHubWebhookController(GitHubWebhookService webhookService) {
    this.webhookService = Objects.requireNonNull(webhookService, "webhookService must not be null");
  }

  @PostMapping("/github")
  public ResponseEntity<WebhookResponse> handleGitHubWebhook(
      @RequestHeader(value = "X-GitHub-Event", required = false) String eventType,
      @RequestHeader(value = "X-GitHub-Delivery", required = false) String deliveryId,
      @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
      @RequestBody(required = false) byte[] rawBody) {
    WebhookProcessingResult result =
        webhookService.handleWebhook(eventType, deliveryId, signature, rawBody);
    HttpStatus status =
        result.status() == WebhookProcessingResult.Status.ACCEPTED
            ? HttpStatus.ACCEPTED
            : HttpStatus.OK;
    return ResponseEntity.status(status).body(WebhookResponse.from(result));
  }
}
