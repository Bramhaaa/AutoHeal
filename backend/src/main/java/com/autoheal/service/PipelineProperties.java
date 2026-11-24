package com.autoheal.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "autoheal.pipeline")
public record PipelineProperties(
    int maxConcurrentRuns,
    int maxQueuedRuns,
    long replayEventDelayMs,
    String demoRepoUri,
    String demoRepoName,
    boolean prCreationEnabled) {

  public PipelineProperties {
    if (maxConcurrentRuns <= 0) {
      maxConcurrentRuns = 3;
    }
    if (maxQueuedRuns <= 0) {
      maxQueuedRuns = 10;
    }
    if (replayEventDelayMs < 0) {
      replayEventDelayMs = 0L;
    }
    if (demoRepoUri == null) {
      demoRepoUri = "";
    }
    if (demoRepoName == null || demoRepoName.isBlank()) {
      demoRepoName = "autoheal-demo-service";
    }
  }
}
