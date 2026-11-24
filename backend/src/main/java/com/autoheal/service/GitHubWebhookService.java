package com.autoheal.service;

import com.autoheal.domain.WebhookFailureEvent;
import com.autoheal.domain.WebhookProcessingResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class GitHubWebhookService {

  private static final String WORKFLOW_RUN_EVENT = "workflow_run";

  private final WebhookSignatureVerifier signatureVerifier;
  private final IncidentOrchestratorService orchestratorService;
  private final ObjectMapper objectMapper;
  private final Set<String> seenDeliveries;

  public GitHubWebhookService(
      WebhookSignatureVerifier signatureVerifier,
      IncidentOrchestratorService orchestratorService,
      ObjectMapper objectMapper,
      WebhookProperties properties) {
    this.signatureVerifier =
        Objects.requireNonNull(signatureVerifier, "signatureVerifier must not be null");
    this.orchestratorService =
        Objects.requireNonNull(orchestratorService, "orchestratorService must not be null");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    int maxEntries = Objects.requireNonNull(properties, "properties").maxDeliveryCacheSize();
    this.seenDeliveries =
        Collections.synchronizedSet(
            Collections.newSetFromMap(
                new LinkedHashMap<>(16, 0.75f, false) {
                  @Override
                  protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > maxEntries;
                  }
                }));
  }

  public WebhookProcessingResult handleWebhook(
      String eventType, String deliveryId, String signatureHeader, byte[] rawBody) {
    byte[] body = rawBody == null ? new byte[0] : rawBody;
    signatureVerifier.verify(body, signatureHeader);

    if (!WORKFLOW_RUN_EVENT.equals(eventType)) {
      return WebhookProcessingResult.ignored("Ignored GitHub event: " + eventType);
    }

    JsonNode root = parsePayload(body);
    String action = root.path("action").asText("");
    String conclusion = root.path("workflow_run").path("conclusion").asText("");
    if (!"completed".equals(action) || !"failure".equals(conclusion)) {
      return WebhookProcessingResult.ignored(
          "Ignored workflow_run with action=" + action + ", conclusion=" + conclusion);
    }

    if (deliveryId == null || deliveryId.isBlank()) {
      throw new IllegalArgumentException("X-GitHub-Delivery header is required");
    }
    if (!seenDeliveries.add(deliveryId)) {
      return WebhookProcessingResult.duplicate(deliveryId);
    }

    WebhookFailureEvent event = extractFailureEvent(deliveryId, root);
    UUID runId = orchestratorService.triggerWebhookRun(event);
    return WebhookProcessingResult.accepted(runId);
  }

  private JsonNode parsePayload(byte[] rawBody) {
    try {
      return objectMapper.readTree(rawBody);
    } catch (IOException e) {
      throw new IllegalArgumentException("Invalid JSON webhook payload", e);
    }
  }

  private static WebhookFailureEvent extractFailureEvent(String deliveryId, JsonNode root) {
    JsonNode workflowRun = root.path("workflow_run");
    JsonNode repository = root.path("repository");

    long workflowRunId = workflowRun.path("id").asLong(0L);
    String headBranch = requireField(workflowRun, "head_branch", "workflow_run.head_branch");
    String headSha = requireField(workflowRun, "head_sha", "workflow_run.head_sha");
    String repo = requireField(repository, "name", "repository.name");
    String owner = requireField(repository.path("owner"), "login", "repository.owner.login");
    String cloneUrl = requireField(repository, "clone_url", "repository.clone_url");

    return new WebhookFailureEvent(
        deliveryId, owner, repo, cloneUrl, headBranch, headSha, workflowRunId);
  }

  private static String requireField(JsonNode node, String field, String label) {
    String value = node.path(field).asText("");
    if (value.isBlank()) {
      throw new IllegalArgumentException("Missing required webhook field: " + label);
    }
    return value;
  }
}
