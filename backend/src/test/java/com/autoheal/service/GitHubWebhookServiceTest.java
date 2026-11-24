package com.autoheal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.autoheal.domain.WebhookFailureEvent;
import com.autoheal.domain.WebhookProcessingResult;
import com.autoheal.domain.WebhookSignatureException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class GitHubWebhookServiceTest {

  private static final String SECRET = "webhook-test-secret";

  private IncidentOrchestratorService orchestratorService;
  private GitHubWebhookService service;

  @BeforeEach
  void setUp() {
    orchestratorService = mock(IncidentOrchestratorService.class);
    WebhookProperties properties = new WebhookProperties(SECRET, 1000);
    WebhookSignatureVerifier verifier = new WebhookSignatureVerifier(properties);
    service =
        new GitHubWebhookService(verifier, orchestratorService, new ObjectMapper(), properties);
  }

  @Test
  void rejectsInvalidSignature() {
    byte[] payload = failurePayload("completed", "failure");

    assertThatThrownBy(() -> service.handleWebhook("workflow_run", "del-1", "sha256=bad", payload))
        .isInstanceOf(WebhookSignatureException.class);
    verify(orchestratorService, never()).triggerWebhookRun(any());
  }

  @Test
  void ignoresNonWorkflowRunEvents() {
    byte[] payload = "{\"ref\":\"refs/heads/main\"}".getBytes(StandardCharsets.UTF_8);
    String sig = WebhookSignatureVerifier.computeSignatureHeader(SECRET, payload);

    WebhookProcessingResult result = service.handleWebhook("push", "del-2", sig, payload);

    assertThat(result.status()).isEqualTo(WebhookProcessingResult.Status.IGNORED);
    verify(orchestratorService, never()).triggerWebhookRun(any());
  }

  @Test
  void ignoresNonFailureOrIncompleteWorkflowRuns() {
    byte[] successPayload = failurePayload("completed", "success");
    String sig1 = WebhookSignatureVerifier.computeSignatureHeader(SECRET, successPayload);
    assertThat(service.handleWebhook("workflow_run", "del-3", sig1, successPayload).status())
        .isEqualTo(WebhookProcessingResult.Status.IGNORED);

    byte[] requestedPayload = failurePayload("requested", "failure");
    String sig2 = WebhookSignatureVerifier.computeSignatureHeader(SECRET, requestedPayload);
    assertThat(service.handleWebhook("workflow_run", "del-4", sig2, requestedPayload).status())
        .isEqualTo(WebhookProcessingResult.Status.IGNORED);

    verify(orchestratorService, never()).triggerWebhookRun(any());
  }

  @Test
  void acceptsFailureAndDeduplicatesByDeliveryId() {
    UUID runId = UUID.randomUUID();
    when(orchestratorService.triggerWebhookRun(any())).thenReturn(runId);

    byte[] payload = failurePayload("completed", "failure");
    String sig = WebhookSignatureVerifier.computeSignatureHeader(SECRET, payload);

    WebhookProcessingResult first =
        service.handleWebhook("workflow_run", "delivery-abc-123", sig, payload);
    WebhookProcessingResult second =
        service.handleWebhook("workflow_run", "delivery-abc-123", sig, payload);

    assertThat(first.status()).isEqualTo(WebhookProcessingResult.Status.ACCEPTED);
    assertThat(first.runId()).isEqualTo(runId);
    assertThat(second.status()).isEqualTo(WebhookProcessingResult.Status.DUPLICATE);

    ArgumentCaptor<WebhookFailureEvent> captor = ArgumentCaptor.forClass(WebhookFailureEvent.class);
    verify(orchestratorService, times(1)).triggerWebhookRun(captor.capture());
    WebhookFailureEvent event = captor.getValue();
    assertThat(event.owner()).isEqualTo("acme");
    assertThat(event.repo()).isEqualTo("autoheal-demo-service");
    assertThat(event.headBranch()).isEqualTo("scenario/payment-npe-01");
    assertThat(event.headSha()).isEqualTo("deadbeef1234");
    assertThat(event.workflowRunId()).isEqualTo(551234L);
  }

  private static byte[] failurePayload(String action, String conclusion) {
    String json =
        """
        {
          "action": "%s",
          "workflow_run": {
            "id": 551234,
            "conclusion": "%s",
            "head_branch": "scenario/payment-npe-01",
            "head_sha": "deadbeef1234"
          },
          "repository": {
            "name": "autoheal-demo-service",
            "clone_url": "https://github.com/acme/autoheal-demo-service.git",
            "owner": {
              "login": "acme"
            }
          }
        }
        """
            .formatted(action, conclusion);
    return json.getBytes(StandardCharsets.UTF_8);
  }
}
