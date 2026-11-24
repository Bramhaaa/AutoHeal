package com.autoheal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.autoheal.domain.SandboxRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SandboxSweeperServiceTest {

  private final Clock fixedClock =
      Clock.fixed(Instant.parse("2026-11-24T12:00:00Z"), ZoneOffset.UTC);

  @Test
  void sweepsExpiredContainersAndOrphanWorkspacesOlderThanOneHour(@TempDir Path workspacesRoot)
      throws Exception {
    SandboxRunner sandboxRunner = mock(SandboxRunner.class);
    when(sandboxRunner.sweepExpiredContainers(Duration.ofSeconds(90))).thenReturn(2);

    Path staleRunDir = Files.createDirectory(workspacesRoot.resolve("run-stale"));
    Files.writeString(staleRunDir.resolve("file.txt"), "old");
    Files.setLastModifiedTime(
        staleRunDir, FileTime.from(fixedClock.instant().minus(Duration.ofHours(2))));

    Path freshRunDir = Files.createDirectory(workspacesRoot.resolve("run-fresh"));
    Files.writeString(freshRunDir.resolve("file.txt"), "new");
    Files.setLastModifiedTime(
        freshRunDir, FileTime.from(fixedClock.instant().minus(Duration.ofMinutes(10))));

    SandboxSweeperService sweeper =
        new SandboxSweeperService(sandboxRunner, workspacesRoot, fixedClock);

    SandboxSweeperService.SweepSummary summary = sweeper.sweep();

    assertThat(summary.containersRemoved()).isEqualTo(2);
    assertThat(summary.workspacesRemoved()).isEqualTo(1);
    assertThat(staleRunDir).doesNotExist();
    assertThat(freshRunDir).exists();
    assertThat(workspacesRoot).exists();
    verify(sandboxRunner).sweepExpiredContainers(Duration.ofSeconds(90));
  }

  @Test
  void handlesNonExistentWorkspacesRootGracefully(@TempDir Path tempDir) {
    SandboxRunner sandboxRunner = mock(SandboxRunner.class);
    when(sandboxRunner.sweepExpiredContainers(Duration.ofSeconds(90))).thenReturn(0);

    Path missingRoot = tempDir.resolve("does-not-exist-yet");
    SandboxSweeperService sweeper =
        new SandboxSweeperService(sandboxRunner, missingRoot, fixedClock);

    SandboxSweeperService.SweepSummary summary = sweeper.sweep();

    assertThat(summary.containersRemoved()).isZero();
    assertThat(summary.workspacesRemoved()).isZero();
  }

  @Test
  void continuesWorkspaceSweepWhenContainerSweepThrowsAndSupportsDefaultRunner(
      @TempDir Path workspacesRoot) throws Exception {
    SandboxRunner failingRunner = mock(SandboxRunner.class);
    when(failingRunner.sweepExpiredContainers(Duration.ofSeconds(90)))
        .thenThrow(new IllegalStateException("docker unavailable"));

    Path staleDir = Files.createDirectory(workspacesRoot.resolve("stale-after-error"));
    Files.setLastModifiedTime(
        staleDir, FileTime.from(fixedClock.instant().minus(Duration.ofHours(3))));

    SandboxSweeperService sweeper =
        new SandboxSweeperService(failingRunner, workspacesRoot, fixedClock);
    SandboxSweeperService.SweepSummary summary = sweeper.sweep();

    assertThat(summary.containersRemoved()).isZero();
    assertThat(summary.workspacesRemoved()).isEqualTo(1);
    assertThat(staleDir).doesNotExist();

    // Default interface method returns 0
    SandboxRunner defaultRunner = (req, cb) -> null;
    assertThat(defaultRunner.sweepExpiredContainers(Duration.ofSeconds(90))).isZero();
  }
}
