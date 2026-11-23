package com.autoheal.domain;

import java.util.List;

public record SandboxResult(
    int exitCode, boolean timedOut, long durationMs, List<String> outputLines) {

  public SandboxResult {
    outputLines = List.copyOf(outputLines);
  }

  public boolean isSuccess() {
    return !timedOut && exitCode == 0;
  }
}
