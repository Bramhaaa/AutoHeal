package com.autoheal.service;

import com.autoheal.domain.GitHubPrClient;
import com.autoheal.domain.HealingResult;
import com.autoheal.domain.PullRequestRequest;
import com.autoheal.domain.PullRequestResult;
import com.autoheal.domain.TestSuiteReport;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class DraftPullRequestService {

  private static final Pattern GITHUB_URI_PATTERN =
      Pattern.compile("github\\.com[:/]([^/]+)/([^/]+?)(?:\\.git)?/?$");

  private final GitHubPrClient gitHubPrClient;
  private final PullRequestBodyFormatter bodyFormatter;
  private final PipelineProperties properties;

  public DraftPullRequestService(
      GitHubPrClient gitHubPrClient,
      PullRequestBodyFormatter bodyFormatter,
      PipelineProperties properties) {
    this.gitHubPrClient = Objects.requireNonNull(gitHubPrClient, "gitHubPrClient");
    this.bodyFormatter = Objects.requireNonNull(bodyFormatter, "bodyFormatter");
    this.properties = Objects.requireNonNull(properties, "properties");
  }

  public Optional<PullRequestResult> openDraftPr(
      UUID runId,
      String repoName,
      String repoUri,
      String baseBranch,
      String baseCommitSha,
      String scenarioId,
      TestSuiteReport baseline,
      HealingResult result) {
    Objects.requireNonNull(runId, "runId must not be null");
    Objects.requireNonNull(baseBranch, "baseBranch must not be null");
    Objects.requireNonNull(baseCommitSha, "baseCommitSha must not be null");
    Objects.requireNonNull(scenarioId, "scenarioId must not be null");
    Objects.requireNonNull(baseline, "baseline must not be null");
    Objects.requireNonNull(result, "result must not be null");

    if (!properties.prCreationEnabled()
        || !result.succeeded()
        || result.patchedFileContents().isEmpty()) {
      return Optional.empty();
    }

    OwnerRepo ownerRepo = resolveOwnerRepo(repoName, repoUri);
    String shortUuid = runId.toString().substring(0, 8);
    String sanitizedScenario = sanitizeBranchSegment(scenarioId);
    String headBranch = "autoheal/fix-" + sanitizedScenario + "-" + shortUuid;
    String title = "[AutoHeal] Fix " + scenarioId + ": " + truncate(result.rootCauseSummary(), 72);
    String body =
        bodyFormatter.format(
            scenarioId, baseline, result, result.modifiedFiles(), result.createdFiles());

    PullRequestRequest request =
        new PullRequestRequest(
            ownerRepo.owner(),
            ownerRepo.repo(),
            baseBranch,
            headBranch,
            baseCommitSha,
            title,
            body,
            result.patchedFileContents());
    return Optional.of(gitHubPrClient.createDraftPullRequest(request));
  }

  static OwnerRepo resolveOwnerRepo(String repoName, String repoUri) {
    if (repoName != null) {
      String trimmed = repoName.trim();
      int slash = trimmed.indexOf('/');
      if (slash > 0 && slash < trimmed.length() - 1 && trimmed.indexOf('/', slash + 1) < 0) {
        return new OwnerRepo(trimmed.substring(0, slash), trimmed.substring(slash + 1));
      }
    }
    if (repoUri != null) {
      Matcher matcher = GITHUB_URI_PATTERN.matcher(repoUri.trim());
      if (matcher.find()) {
        return new OwnerRepo(matcher.group(1), matcher.group(2));
      }
    }
    throw new IllegalStateException(
        "Cannot resolve GitHub owner/repo from repoName='"
            + repoName
            + "' or repoUri='"
            + repoUri
            + "'");
  }

  private static String sanitizeBranchSegment(String raw) {
    String normalized =
        raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]+", "-").replaceAll("^-+|-+$", "");
    if (normalized.isEmpty()) {
      return "incident";
    }
    return normalized.length() > 32 ? normalized.substring(0, 32) : normalized;
  }

  private static String truncate(String text, int maxLen) {
    if (text == null || text.isBlank()) {
      return "automated fix";
    }
    String singleLine = text.replace('\n', ' ').replace('\r', ' ').trim();
    return singleLine.length() <= maxLen ? singleLine : singleLine.substring(0, maxLen - 3) + "...";
  }

  record OwnerRepo(String owner, String repo) {}
}
