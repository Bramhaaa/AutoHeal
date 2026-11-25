package com.autoheal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.autoheal.domain.GitBranchInfo;
import com.autoheal.domain.GitHubException;
import com.autoheal.domain.GitHubRepoHygieneClient;
import com.autoheal.domain.OpenPullRequest;
import com.autoheal.service.DemoRepoHygieneService.HygieneSummary;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class DemoRepoHygieneServiceTest {

  private static final Instant NOW = Instant.parse("2026-11-25T03:00:00Z");
  private static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  @Test
  void skipsCleanupWhenPrCreationIsDisabled() {
    GitHubRepoHygieneClient client = mock(GitHubRepoHygieneClient.class);
    PipelineProperties props =
        new PipelineProperties(3, 10, 0L, "https://github.com/acme/demo.git", "acme/demo", false);
    DemoRepoHygieneService service = new DemoRepoHygieneService(client, props, FIXED_CLOCK);

    HygieneSummary summary = service.cleanStaleDemoArtifacts();

    assertThat(summary).isEqualTo(new HygieneSummary(0, 0));
    verify(client, never()).listOpenPullRequests(anyString(), anyString());
    verify(client, never()).listBranchesWithPrefix(anyString(), anyString(), anyString());
  }

  @Test
  void closesOnlyStaleAutohealPrsAndDeletesOnlyStaleAutohealBranches() {
    GitHubRepoHygieneClient client = mock(GitHubRepoHygieneClient.class);
    PipelineProperties props =
        new PipelineProperties(3, 10, 0L, "https://github.com/acme/demo.git", "acme/demo", true);

    Instant stale = NOW.minus(Duration.ofHours(25));
    Instant fresh = NOW.minus(Duration.ofHours(2));

    when(client.listOpenPullRequests("acme", "demo"))
        .thenReturn(
            List.of(
                new OpenPullRequest(10, "autoheal/fix-payment-npe-01-aaaa1111", stale),
                new OpenPullRequest(11, "autoheal/fix-order-jpql-04-bbbb2222", fresh),
                new OpenPullRequest(12, "feature/manual-work", stale)));

    when(client.listBranchesWithPrefix("acme", "demo", "autoheal/"))
        .thenReturn(
            List.of(
                new GitBranchInfo("autoheal/fix-payment-npe-01-aaaa1111", stale),
                new GitBranchInfo("autoheal/fix-order-jpql-04-bbbb2222", fresh),
                new GitBranchInfo("scenario/payment-npe-01", stale)));

    DemoRepoHygieneService service = new DemoRepoHygieneService(client, props, FIXED_CLOCK);
    HygieneSummary summary = service.cleanStaleDemoArtifacts();

    assertThat(summary).isEqualTo(new HygieneSummary(1, 1));
    verify(client).closePullRequest("acme", "demo", 10);
    verify(client, never()).closePullRequest("acme", "demo", 11);
    verify(client, never()).closePullRequest("acme", "demo", 12);
    verify(client).deleteBranch("acme", "demo", "autoheal/fix-payment-npe-01-aaaa1111");
    verify(client, never()).deleteBranch("acme", "demo", "autoheal/fix-order-jpql-04-bbbb2222");
    verify(client, never()).deleteBranch("acme", "demo", "scenario/payment-npe-01");
  }

  @Test
  void isolatesGitHubErrorsSoSchedulerDoesNotCrash() {
    GitHubRepoHygieneClient client = mock(GitHubRepoHygieneClient.class);
    PipelineProperties props =
        new PipelineProperties(3, 10, 0L, "https://github.com/acme/demo.git", "acme/demo", true);
    when(client.listOpenPullRequests("acme", "demo")).thenThrow(new GitHubException("GitHub 503"));

    DemoRepoHygieneService service = new DemoRepoHygieneService(client, props, FIXED_CLOCK);
    HygieneSummary summary = service.cleanStaleDemoArtifacts();

    assertThat(summary).isEqualTo(new HygieneSummary(0, 0));
    verify(client, never()).closePullRequest(anyString(), anyString(), anyInt());
  }
}
