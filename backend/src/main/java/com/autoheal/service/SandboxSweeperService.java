package com.autoheal.service;

import com.autoheal.domain.SandboxRunner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Objects;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class SandboxSweeperService {

  private static final Logger log = LoggerFactory.getLogger(SandboxSweeperService.class);
  static final Duration CONTAINER_MAX_AGE = Duration.ofSeconds(90);
  static final Duration WORKSPACE_MAX_AGE = Duration.ofHours(1);

  private final SandboxRunner sandboxRunner;
  private final Path workspacesRoot;
  private final Clock clock;

  @Autowired
  public SandboxSweeperService(
      SandboxRunner sandboxRunner,
      @Value("${autoheal.workspace.root:${java.io.tmpdir}/autoheal-runs}") String workspacesRoot) {
    this(sandboxRunner, Path.of(workspacesRoot), Clock.systemUTC());
  }

  SandboxSweeperService(SandboxRunner sandboxRunner, Path workspacesRoot, Clock clock) {
    this.sandboxRunner = Objects.requireNonNull(sandboxRunner, "sandboxRunner");
    this.workspacesRoot = Objects.requireNonNull(workspacesRoot, "workspacesRoot");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Scheduled(fixedDelayString = "${autoheal.sweeper.interval-ms:60000}")
  public SweepSummary sweep() {
    int containersRemoved = sweepContainersSafely();
    int workspacesRemoved = sweepOrphanWorkspaces();
    if (containersRemoved > 0 || workspacesRemoved > 0) {
      log.info(
          "Sweeper removed {} expired sandbox container(s) and {} orphan workspace(s)",
          containersRemoved,
          workspacesRemoved);
    }
    return new SweepSummary(containersRemoved, workspacesRemoved);
  }

  private int sweepContainersSafely() {
    try {
      return sandboxRunner.sweepExpiredContainers(CONTAINER_MAX_AGE);
    } catch (RuntimeException e) {
      // Scheduled sweeper boundary: log Docker connectivity error without killing the scheduler.
      log.warn("Failed to sweep expired sandbox containers: {}", e.getMessage());
      return 0;
    }
  }

  private int sweepOrphanWorkspaces() {
    if (!Files.isDirectory(workspacesRoot)) {
      return 0;
    }
    Instant cutoff = clock.instant().minus(WORKSPACE_MAX_AGE);
    int removed = 0;
    try (Stream<Path> entries = Files.list(workspacesRoot)) {
      for (Path entry : entries.toList()) {
        if (Files.isDirectory(entry) && isOlderThan(entry, cutoff)) {
          deleteRecursively(entry);
          removed++;
        }
      }
    } catch (IOException e) {
      log.warn("Failed to list workspaces under {}: {}", workspacesRoot, e.getMessage());
    }
    return removed;
  }

  private static boolean isOlderThan(Path dir, Instant cutoff) {
    try {
      return Files.getLastModifiedTime(dir).toInstant().isBefore(cutoff);
    } catch (IOException e) {
      return false;
    }
  }

  private static void deleteRecursively(Path dir) {
    try (Stream<Path> walk = Files.walk(dir)) {
      walk.sorted(Comparator.reverseOrder())
          .forEach(
              path -> {
                try {
                  Files.deleteIfExists(path);
                } catch (IOException ignored) {
                  log.debug("Could not delete orphan path {}", path);
                }
              });
    } catch (IOException e) {
      log.warn("Failed to delete orphan workspace {}: {}", dir, e.getMessage());
    }
  }

  public record SweepSummary(int containersRemoved, int workspacesRemoved) {}
}
