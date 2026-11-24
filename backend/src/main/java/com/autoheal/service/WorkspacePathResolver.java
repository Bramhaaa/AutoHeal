package com.autoheal.service;

import com.autoheal.domain.PathTraversalException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class WorkspacePathResolver {

  public Path resolveSafe(Path workspaceRoot, String relativePath) {
    Objects.requireNonNull(workspaceRoot, "workspaceRoot must not be null");
    if (relativePath == null || relativePath.isBlank()) {
      throw new PathTraversalException("Path must not be null or blank");
    }
    return resolveNormalized(workspaceRoot, relativePath);
  }

  public Path resolveSafeDirectory(Path workspaceRoot, String relativeDir) {
    Objects.requireNonNull(workspaceRoot, "workspaceRoot must not be null");
    if (relativeDir == null || relativeDir.isBlank() || ".".equals(relativeDir.trim())) {
      return toRealRoot(workspaceRoot);
    }
    return resolveNormalized(workspaceRoot, relativeDir);
  }

  public String toRelativePosixPath(Path workspaceRoot, Path resolvedPath) {
    Path realRoot = toRealRoot(workspaceRoot);
    Path relative = realRoot.relativize(resolvedPath.normalize());
    return relative.toString().replace('\\', '/');
  }

  public boolean isWithinWorkspace(Path workspaceRoot, Path candidate) {
    Path realRoot = toRealRoot(workspaceRoot);
    try {
      Path realCandidate = candidate.toRealPath();
      return realCandidate.startsWith(realRoot);
    } catch (IOException e) {
      return false;
    }
  }

  private Path resolveNormalized(Path workspaceRoot, String relativePath) {
    if (relativePath.indexOf('\0') >= 0) {
      throw new PathTraversalException("Path contains NUL byte: " + relativePath);
    }
    if (relativePath.startsWith("/") || relativePath.startsWith("\\")) {
      throw new PathTraversalException("Absolute paths are not allowed: " + relativePath);
    }

    Path parsed;
    try {
      parsed = Path.of(relativePath);
    } catch (InvalidPathException e) {
      throw new PathTraversalException("Invalid path: " + relativePath, e);
    }
    if (parsed.isAbsolute()) {
      throw new PathTraversalException("Absolute paths are not allowed: " + relativePath);
    }

    Path realRoot = toRealRoot(workspaceRoot);
    Path candidate = realRoot.resolve(parsed).normalize();
    if (!candidate.startsWith(realRoot)) {
      throw new PathTraversalException("Path escapes workspace root: " + relativePath);
    }

    if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
      verifyRealPathWithinRoot(realRoot, candidate, relativePath);
    } else {
      Path ancestor = candidate.getParent();
      while (ancestor != null && !Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) {
        ancestor = ancestor.getParent();
      }
      if (ancestor == null) {
        throw new PathTraversalException(
            "Path has no valid ancestor inside workspace: " + relativePath);
      }
      verifyRealPathWithinRoot(realRoot, ancestor, relativePath);
    }

    return candidate;
  }

  private void verifyRealPathWithinRoot(Path realRoot, Path pathToCheck, String originalInput) {
    try {
      Path realTarget = pathToCheck.toRealPath();
      if (!realTarget.startsWith(realRoot)) {
        throw new PathTraversalException("Symlink escapes workspace root: " + originalInput);
      }
    } catch (IOException e) {
      throw new PathTraversalException("Unable to verify real path: " + originalInput, e);
    }
  }

  private Path toRealRoot(Path workspaceRoot) {
    try {
      return workspaceRoot.toRealPath();
    } catch (IOException e) {
      throw new UncheckedIOException("Workspace root does not exist: " + workspaceRoot, e);
    }
  }
}
