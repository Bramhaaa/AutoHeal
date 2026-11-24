package com.autoheal.domain;

import java.nio.file.Path;
import java.util.Objects;

public record HealingContext(
    Path workspaceRoot,
    DistilledLog baselineLog,
    TestSuiteReport baselineReport,
    String recentGitDiffs) {

  public HealingContext {
    Objects.requireNonNull(workspaceRoot, "workspaceRoot must not be null");
    Objects.requireNonNull(baselineLog, "baselineLog must not be null");
    Objects.requireNonNull(baselineReport, "baselineReport must not be null");
    Objects.requireNonNull(recentGitDiffs, "recentGitDiffs must not be null");
  }
}
