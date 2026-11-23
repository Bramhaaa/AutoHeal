package com.autoheal.domain;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

public record SandboxRequest(Path workspaceDir, Duration timeout) {

  public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

  public SandboxRequest {
    Objects.requireNonNull(workspaceDir, "workspaceDir must not be null");
    Objects.requireNonNull(timeout, "timeout must not be null");
    if (timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("timeout must be positive");
    }
  }
}
