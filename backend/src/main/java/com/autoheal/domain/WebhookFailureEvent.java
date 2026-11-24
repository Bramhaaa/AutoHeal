package com.autoheal.domain;

import java.util.Objects;

public record WebhookFailureEvent(
    String deliveryId,
    String owner,
    String repo,
    String cloneUrl,
    String headBranch,
    String headSha,
    long workflowRunId) {

  public WebhookFailureEvent {
    Objects.requireNonNull(deliveryId, "deliveryId must not be null");
    Objects.requireNonNull(owner, "owner must not be null");
    Objects.requireNonNull(repo, "repo must not be null");
    Objects.requireNonNull(cloneUrl, "cloneUrl must not be null");
    Objects.requireNonNull(headBranch, "headBranch must not be null");
    Objects.requireNonNull(headSha, "headSha must not be null");
    if (deliveryId.isBlank()) {
      throw new IllegalArgumentException("deliveryId must not be blank");
    }
    if (workflowRunId <= 0) {
      throw new IllegalArgumentException("workflowRunId must be positive");
    }
  }

  public String fullRepoName() {
    return owner + "/" + repo;
  }
}
