package com.autoheal.domain;

import java.util.Objects;

public record PullRequestResult(int number, String htmlUrl, String branchName) {

  public PullRequestResult {
    Objects.requireNonNull(htmlUrl, "htmlUrl must not be null");
    Objects.requireNonNull(branchName, "branchName must not be null");
  }
}
