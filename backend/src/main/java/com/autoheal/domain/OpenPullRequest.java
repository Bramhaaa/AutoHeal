package com.autoheal.domain;

import java.time.Instant;
import java.util.Objects;

public record OpenPullRequest(int number, String headBranch, Instant createdAt) {

  public OpenPullRequest {
    Objects.requireNonNull(headBranch, "headBranch must not be null");
    Objects.requireNonNull(createdAt, "createdAt must not be null");
  }
}
