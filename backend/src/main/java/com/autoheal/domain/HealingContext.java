package com.autoheal.domain;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public record HealingContext(
    Path workspaceRoot,
    DistilledLog baselineLog,
    TestSuiteReport baselineReport,
    String recentGitDiffs,
    List<IncidentMemoryEntry> historicalMatches) {

  public HealingContext {
    Objects.requireNonNull(workspaceRoot, "workspaceRoot must not be null");
    Objects.requireNonNull(baselineLog, "baselineLog must not be null");
    Objects.requireNonNull(baselineReport, "baselineReport must not be null");
    Objects.requireNonNull(recentGitDiffs, "recentGitDiffs must not be null");
    historicalMatches = historicalMatches == null ? List.of() : List.copyOf(historicalMatches);
  }

  public HealingContext(
      Path workspaceRoot,
      DistilledLog baselineLog,
      TestSuiteReport baselineReport,
      String recentGitDiffs) {
    this(workspaceRoot, baselineLog, baselineReport, recentGitDiffs, List.of());
  }
}
