package com.autoheal.domain;

import java.util.Map;
import java.util.Objects;

public record PullRequestRequest(
    String owner,
    String repo,
    String baseBranch,
    String headBranch,
    String baseCommitSha,
    String title,
    String body,
    Map<String, String> fileContents) {

  public PullRequestRequest {
    Objects.requireNonNull(owner, "owner must not be null");
    Objects.requireNonNull(repo, "repo must not be null");
    Objects.requireNonNull(baseBranch, "baseBranch must not be null");
    Objects.requireNonNull(headBranch, "headBranch must not be null");
    Objects.requireNonNull(baseCommitSha, "baseCommitSha must not be null");
    Objects.requireNonNull(title, "title must not be null");
    Objects.requireNonNull(body, "body must not be null");
    fileContents =
        Map.copyOf(Objects.requireNonNull(fileContents, "fileContents must not be null"));
  }
}
