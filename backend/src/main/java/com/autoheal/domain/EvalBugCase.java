package com.autoheal.domain;

import java.util.Objects;

public record EvalBugCase(
    String id, String category, String repoUri, String branch, String description) {

  public EvalBugCase {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(category, "category must not be null");
    Objects.requireNonNull(repoUri, "repoUri must not be null");
    Objects.requireNonNull(branch, "branch must not be null");
    description = description == null ? "" : description;
  }
}
