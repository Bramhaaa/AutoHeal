package com.autoheal.domain;

import java.nio.file.Path;

public interface WorkspaceManager {

  WorkspaceHandle cloneBranch(String repoUri, String branch);

  String readRecentDiffs(Path workspaceDir, int maxCommits);
}
