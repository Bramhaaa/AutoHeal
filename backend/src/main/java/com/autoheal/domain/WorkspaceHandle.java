package com.autoheal.domain;

import java.nio.file.Path;
import java.util.Objects;

public record WorkspaceHandle(Path directory, String commitSha, Runnable cleanupAction)
    implements AutoCloseable {

  public WorkspaceHandle {
    Objects.requireNonNull(directory, "directory must not be null");
    Objects.requireNonNull(commitSha, "commitSha must not be null");
    Objects.requireNonNull(cleanupAction, "cleanupAction must not be null");
  }

  @Override
  public void close() {
    cleanupAction.run();
  }
}
