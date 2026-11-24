package com.autoheal.domain;

public interface GitHubPrClient {

  PullRequestResult createDraftPullRequest(PullRequestRequest request);
}
