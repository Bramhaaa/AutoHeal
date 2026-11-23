package com.autoheal.infra.git;

import static org.assertj.core.api.Assertions.assertThat;

import com.autoheal.domain.WorkspaceHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JGitWorkspaceManagerTest {

  @Test
  void clonesRequestedBranchReadsDiffsAndDeletesWorkspaceOnClose(@TempDir Path tempDir)
      throws Exception {
    Path originRepo = tempDir.resolve("origin");
    Path workspacesRoot = tempDir.resolve("workspaces");
    Files.createDirectories(originRepo);

    String expectedBugSha;
    try (Git git = Git.init().setDirectory(originRepo.toFile()).setInitialBranch("main").call()) {
      Files.writeString(
          originRepo.resolve("Hello.java"), "class Hello { int v() { return 1; } }\n");
      git.add().addFilepattern(".").call();
      git.commit().setMessage("initial green").call();

      git.checkout().setCreateBranch(true).setName("scenario/demo-bug").call();
      Files.writeString(
          originRepo.resolve("Hello.java"), "class Hello { int v() { return 0; } }\n");
      git.add().addFilepattern(".").call();
      expectedBugSha = git.commit().setMessage("introduce bug").call().getName();
    }

    JGitWorkspaceManager manager = new JGitWorkspaceManager(workspacesRoot);
    Path clonedDir;
    try (WorkspaceHandle handle =
        manager.cloneBranch(originRepo.toUri().toString(), "scenario/demo-bug")) {
      clonedDir = handle.directory();
      assertThat(handle.commitSha()).isEqualTo(expectedBugSha);
      assertThat(Files.readString(clonedDir.resolve("Hello.java"))).contains("return 0;");

      String diffs = manager.readRecentDiffs(clonedDir, 2);
      assertThat(diffs)
          .contains("introduce bug")
          .contains("-class Hello { int v() { return 1; } }");
    }

    assertThat(clonedDir).doesNotExist();
  }
}
