package com.autoheal.domain;

import java.time.Instant;
import java.util.Objects;

public record GitBranchInfo(String name, Instant committedAt) {

  public GitBranchInfo {
    Objects.requireNonNull(name, "name must not be null");
    Objects.requireNonNull(committedAt, "committedAt must not be null");
  }
}
