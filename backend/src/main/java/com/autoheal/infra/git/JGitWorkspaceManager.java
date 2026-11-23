package com.autoheal.infra.git;

import com.autoheal.domain.WorkspaceHandle;
import com.autoheal.domain.WorkspaceManager;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JGitWorkspaceManager implements WorkspaceManager {

  private static final Logger log = LoggerFactory.getLogger(JGitWorkspaceManager.class);
  private static final int MAX_DIFF_CHARS = 16_000;

  private final Path workspacesRoot;

  public JGitWorkspaceManager(Path workspacesRoot) {
    this.workspacesRoot =
        Objects.requireNonNull(workspacesRoot, "workspacesRoot must not be null")
            .toAbsolutePath()
            .normalize();
  }

  @Override
  public WorkspaceHandle cloneBranch(String repoUri, String branch) {
    Objects.requireNonNull(repoUri, "repoUri must not be null");
    Objects.requireNonNull(branch, "branch must not be null");

    Path runDir = workspacesRoot.resolve("run-" + UUID.randomUUID());
    try {
      Files.createDirectories(runDir);
      try (Git git =
          Git.cloneRepository()
              .setURI(repoUri)
              .setDirectory(runDir.toFile())
              .setBranchesToClone(List.of("refs/heads/" + branch))
              .setBranch("refs/heads/" + branch)
              .call()) {
        ObjectId head = git.getRepository().resolve("HEAD");
        if (head == null) {
          throw new IllegalStateException("Cloned repository has no HEAD commit");
        }
        return new WorkspaceHandle(runDir, head.getName(), () -> deleteQuietly(runDir));
      }
    } catch (IOException | GitAPIException e) {
      deleteQuietly(runDir);
      throw new IllegalStateException("Failed to clone branch '" + branch + "' from " + repoUri, e);
    }
  }

  @Override
  public String readRecentDiffs(Path workspaceDir, int maxCommits) {
    if (maxCommits <= 0) {
      return "";
    }
    StringBuilder out = new StringBuilder();
    try (Git git = Git.open(workspaceDir.toFile())) {
      Repository repo = git.getRepository();
      Iterable<RevCommit> commits = git.log().setMaxCount(maxCommits).call();
      for (RevCommit commit : commits) {
        out.append("commit ")
            .append(commit.getName())
            .append("\n")
            .append(commit.getFullMessage().trim())
            .append("\n");
        out.append(formatCommitDiff(repo, commit)).append("\n");
        if (out.length() >= MAX_DIFF_CHARS) {
          return out.substring(0, MAX_DIFF_CHARS);
        }
      }
      return out.toString();
    } catch (IOException | GitAPIException e) {
      throw new IllegalStateException("Failed to read Git diffs in " + workspaceDir, e);
    }
  }

  private static String formatCommitDiff(Repository repo, RevCommit commit) throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    try (ObjectReader reader = repo.newObjectReader();
        DiffFormatter formatter = new DiffFormatter(buffer)) {
      formatter.setRepository(repo);
      CanonicalTreeParser newTree = new CanonicalTreeParser();
      newTree.reset(reader, commit.getTree().getId());

      if (commit.getParentCount() > 0) {
        CanonicalTreeParser oldTree = new CanonicalTreeParser();
        oldTree.reset(reader, commit.getParent(0).getTree().getId());
        formatter.format(oldTree, newTree);
      } else {
        formatter.format(new EmptyTreeIterator(), newTree);
      }
      formatter.flush();
    }
    return buffer.toString(StandardCharsets.UTF_8);
  }

  private static void deleteQuietly(Path dir) {
    if (!Files.exists(dir)) {
      return;
    }
    try (Stream<Path> walk = Files.walk(dir)) {
      walk.sorted(Comparator.reverseOrder())
          .forEach(
              path -> {
                try {
                  Files.deleteIfExists(path);
                } catch (IOException e) {
                  throw new UncheckedIOException(e);
                }
              });
    } catch (IOException | UncheckedIOException e) {
      log.warn("Failed to clean up workspace directory {}", dir, e);
    }
  }
}
