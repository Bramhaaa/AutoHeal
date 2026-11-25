package com.autoheal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.autoheal.domain.AttemptOutcome;
import com.autoheal.domain.AttemptRecord;
import com.autoheal.domain.GitHubPrClient;
import com.autoheal.domain.HealingResult;
import com.autoheal.domain.LlmUsage;
import com.autoheal.domain.PullRequestRequest;
import com.autoheal.domain.PullRequestResult;
import com.autoheal.domain.TestCaseId;
import com.autoheal.domain.TestSuiteReport;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DraftPullRequestServiceTest {

  private final PullRequestBodyFormatter bodyFormatter = new PullRequestBodyFormatter();
  private final TestSuiteReport baseline =
      new TestSuiteReport(
          5, 1, 0, 0, Set.of(), Set.of(new TestCaseId("PaymentServiceTest", "testNull")));

  @Test
  void skipsPrCreationWhenFeatureFlagDisabled() {
    GitHubPrClient prClient = mock(GitHubPrClient.class);
    PipelineProperties props =
        new PipelineProperties(3, 10, 0L, "https://github.com/acme/demo.git", "acme/demo", false);
    DraftPullRequestService service = new DraftPullRequestService(prClient, bodyFormatter, props);

    Optional<PullRequestResult> result =
        service.openDraftPr(
            UUID.randomUUID(),
            "acme/demo",
            "https://github.com/acme/demo.git",
            "scenario/payment-npe",
            "abc1234",
            "payment-npe-01",
            baseline,
            verifiedHealingResult());

    assertThat(result).isEmpty();
    verify(prClient, never()).createDraftPullRequest(any());
  }

  @Test
  void createsDraftPrWithFormattedBodyAndAutohealBranchWhenEnabled() {
    GitHubPrClient prClient = mock(GitHubPrClient.class);
    PipelineProperties props =
        new PipelineProperties(3, 10, 0L, "https://github.com/acme/demo.git", "acme/demo", true);
    DraftPullRequestService service = new DraftPullRequestService(prClient, bodyFormatter, props);
    UUID runId = UUID.fromString("3f1c9a2e-7b4d-4c1e-9a8f-2d6b5e0c1a77");

    when(prClient.createDraftPullRequest(any()))
        .thenReturn(
            new PullRequestResult(
                42,
                "https://github.com/acme/demo/pull/42",
                "autoheal/fix-payment-npe-01-3f1c9a2e"));

    Optional<PullRequestResult> result =
        service.openDraftPr(
            runId,
            "acme/demo",
            "https://github.com/acme/demo.git",
            "scenario/payment-npe",
            "abc1234567890",
            "payment-npe-01",
            baseline,
            verifiedHealingResult());

    assertThat(result).isPresent();
    assertThat(result.get().htmlUrl()).isEqualTo("https://github.com/acme/demo/pull/42");

    ArgumentCaptor<PullRequestRequest> captor = ArgumentCaptor.forClass(PullRequestRequest.class);
    verify(prClient).createDraftPullRequest(captor.capture());
    PullRequestRequest req = captor.getValue();
    assertThat(req.owner()).isEqualTo("acme");
    assertThat(req.repo()).isEqualTo("demo");
    assertThat(req.baseBranch()).isEqualTo("scenario/payment-npe");
    assertThat(req.headBranch()).isEqualTo("autoheal/fix-payment-npe-01-3f1c9a2e");
    assertThat(req.baseCommitSha()).isEqualTo("abc1234567890");
    assertThat(req.title()).contains("AutoHeal").contains("payment-npe-01");
    assertThat(req.body())
        .contains("## Root Cause Analysis")
        .contains("Added null guard for discount")
        .contains("PaymentServiceTest#testNull");
    assertThat(req.fileContents())
        .containsEntry("src/main/java/com/demo/PaymentService.java", "class PaymentService {}");
  }

  @Test
  void parsesOwnerAndRepoFromGithubHttpsUriWhenRepoNameHasNoSlash() {
    GitHubPrClient prClient = mock(GitHubPrClient.class);
    PipelineProperties props =
        new PipelineProperties(
            3, 10, 0L, "https://github.com/org-one/autoheal-demo-service.git", "demo", true);
    DraftPullRequestService service = new DraftPullRequestService(prClient, bodyFormatter, props);

    when(prClient.createDraftPullRequest(any()))
        .thenReturn(
            new PullRequestResult(
                7,
                "https://github.com/org-one/autoheal-demo-service/pull/7",
                "autoheal/fix-payment-npe-01-11111111"));

    Optional<PullRequestResult> result =
        service.openDraftPr(
            UUID.fromString("11111111-2222-3333-4444-555555555555"),
            "autoheal-demo-service",
            "https://github.com/org-one/autoheal-demo-service.git",
            "main",
            "deadbeef",
            "payment-npe-01",
            baseline,
            verifiedHealingResult());

    assertThat(result).isPresent();
    ArgumentCaptor<PullRequestRequest> captor = ArgumentCaptor.forClass(PullRequestRequest.class);
    verify(prClient).createDraftPullRequest(captor.capture());
    assertThat(captor.getValue().owner()).isEqualTo("org-one");
    assertThat(captor.getValue().repo()).isEqualTo("autoheal-demo-service");
  }

  @Test
  void returnsEmptyWhenPatchedFilesAreEmpty() {
    GitHubPrClient prClient = mock(GitHubPrClient.class);
    PipelineProperties props =
        new PipelineProperties(3, 10, 0L, "https://github.com/acme/demo.git", "acme/demo", true);
    DraftPullRequestService service = new DraftPullRequestService(prClient, bodyFormatter, props);

    AttemptRecord verified =
        new AttemptRecord(1, AttemptOutcome.VERIFIED, "--- diff ---", 0, 5, 0, 1000L, "Verified");
    HealingResult emptyFilesResult =
        HealingResult.succeeded("Fixed", "--- diff ---", List.of(verified), List.of());

    Optional<PullRequestResult> result =
        service.openDraftPr(
            UUID.randomUUID(),
            "acme/demo",
            "https://github.com/acme/demo.git",
            "main",
            "abc123",
            "payment-npe-01",
            baseline,
            emptyFilesResult);

    assertThat(result).isEmpty();
    verify(prClient, never()).createDraftPullRequest(any());
  }

  private HealingResult verifiedHealingResult() {
    AttemptRecord verified =
        new AttemptRecord(1, AttemptOutcome.VERIFIED, "--- diff ---", 0, 5, 0, 8000L, "Verified");
    LlmUsage usage = new LlmUsage("gemini-2.5-flash", 900, 180, new BigDecimal("0.000243"), 410L);
    return HealingResult.succeeded(
        "Added null guard for discount",
        "--- diff ---",
        List.of("src/main/java/com/demo/PaymentService.java"),
        List.of(),
        Map.of("src/main/java/com/demo/PaymentService.java", "class PaymentService {}"),
        List.of(verified),
        List.of(usage));
  }
}
