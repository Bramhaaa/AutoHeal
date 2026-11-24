package com.autoheal.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.autoheal.domain.WebhookProcessingResult;
import com.autoheal.domain.WebhookSignatureException;
import com.autoheal.service.GitHubWebhookService;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class GitHubWebhookControllerTest {

  private GitHubWebhookService webhookService;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    webhookService = mock(GitHubWebhookService.class);
    mockMvc =
        MockMvcBuilders.standaloneSetup(new GitHubWebhookController(webhookService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  void returns202AcceptedWhenWebhookTriggersRun() throws Exception {
    UUID runId = UUID.randomUUID();
    when(webhookService.handleWebhook(eq("workflow_run"), eq("del-1"), eq("sha256=ok"), any()))
        .thenReturn(WebhookProcessingResult.accepted(runId));

    mockMvc
        .perform(
            post("/api/v1/webhooks/github")
                .header("X-GitHub-Event", "workflow_run")
                .header("X-GitHub-Delivery", "del-1")
                .header("X-Hub-Signature-256", "sha256=ok")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"completed\"}".getBytes(StandardCharsets.UTF_8)))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.status").value("ACCEPTED"))
        .andExpect(jsonPath("$.runId").value(runId.toString()));
  }

  @Test
  void returns200OkWhenWebhookIgnoredOrDuplicate() throws Exception {
    when(webhookService.handleWebhook(eq("push"), eq("del-2"), eq("sha256=ok"), any()))
        .thenReturn(WebhookProcessingResult.ignored("Ignored non-workflow_run event"));

    mockMvc
        .perform(
            post("/api/v1/webhooks/github")
                .header("X-GitHub-Event", "push")
                .header("X-GitHub-Delivery", "del-2")
                .header("X-Hub-Signature-256", "sha256=ok")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}".getBytes(StandardCharsets.UTF_8)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("IGNORED"));
  }

  @Test
  void returns401UnauthorizedWhenSignatureInvalid() throws Exception {
    when(webhookService.handleWebhook(any(), any(), any(), any()))
        .thenThrow(new WebhookSignatureException("Invalid webhook signature"));

    mockMvc
        .perform(
            post("/api/v1/webhooks/github")
                .header("X-GitHub-Event", "workflow_run")
                .header("X-GitHub-Delivery", "del-3")
                .header("X-Hub-Signature-256", "sha256=bad")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}".getBytes(StandardCharsets.UTF_8)))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("INVALID_WEBHOOK_SIGNATURE"));
  }
}
