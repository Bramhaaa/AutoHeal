package com.autoheal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.autoheal.domain.AttemptOutcome;
import com.autoheal.domain.AttemptRecord;
import com.autoheal.domain.HealingResult;
import com.autoheal.domain.IncidentAttempt;
import com.autoheal.domain.IncidentEvent;
import com.autoheal.domain.IncidentEventType;
import com.autoheal.domain.IncidentRepository;
import com.autoheal.domain.IncidentRun;
import com.autoheal.domain.IncidentSource;
import com.autoheal.domain.IncidentStage;
import com.autoheal.domain.IncidentStatus;
import com.autoheal.domain.LlmCallRecord;
import com.autoheal.domain.LlmUsage;
import com.autoheal.domain.SandboxResult;
import com.autoheal.domain.SandboxRunner;
import com.autoheal.domain.ScenarioDefinition;
import com.autoheal.domain.TestCaseId;
import com.autoheal.domain.TestSuiteReport;
import com.autoheal.domain.WorkspaceHandle;
import com.autoheal.domain.WorkspaceManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DefaultLiveIncidentExecutorTest {

  private final ScenarioDefinition scenario = new ScenarioCatalog().getRequired("payment-npe-01");

  @Test
  void executesFullLivePipelineAndPersistsSuccessMetrics(@TempDir Path tempDir) {
    WorkspaceManager workspaceManager = mock(WorkspaceManager.class);
    SandboxRunner sandboxRunner = mock(SandboxRunner.class);
    SurefireReportParser surefireParser = mock(SurefireReportParser.class);
    AgenticHealingService healingService = mock(AgenticHealingService.class);
    RecordingRepository repo = new RecordingRepository();
    IncidentEventStreamService stream = new IncidentEventStreamService(repo, new ObjectMapper());
    PipelineProperties props =
        new PipelineProperties(3, 10, 0L, "https://github.com/owner/demo.git", "owner/demo", false);

    when(workspaceManager.cloneBranch(props.demoRepoUri(), scenario.branch()))
        .thenReturn(new WorkspaceHandle(tempDir, "abc123", () -> {}));
    when(workspaceManager.readRecentDiffs(tempDir, 3)).thenReturn("diff --git a/X.java b/X.java");
    when(sandboxRunner.runTests(any(), any()))
        .thenReturn(new SandboxResult(1, false, 1200L, List.of("NullPointerException in Payment")));
    when(surefireParser.parse(tempDir))
        .thenReturn(
            new TestSuiteReport(
                5, 1, 0, 0, Set.of(), Set.of(new TestCaseId("PaymentServiceTest", "testNull"))));

    AttemptRecord verified =
        new AttemptRecord(1, AttemptOutcome.VERIFIED, "--- diff ---", 0, 5, 0, 8000L, "Verified");
    LlmUsage usage = new LlmUsage("gemini-2.5-flash", 900, 180, new BigDecimal("0.000243"), 410L);
    when(healingService.heal(any(), any()))
        .thenReturn(
            HealingResult.succeeded(
                "Added null guard for discount",
                "--- diff ---",
                List.of(verified),
                List.of(usage)));

    DefaultLiveIncidentExecutor executor =
        new DefaultLiveIncidentExecutor(
            workspaceManager,
            sandboxRunner,
            surefireParser,
            new LogDistiller(),
            healingService,
            repo,
            stream,
            props);

    UUID runId = UUID.randomUUID();
    repo.createRun(
        IncidentRun.queued(
            runId,
            IncidentSource.PLAYGROUND,
            scenario.id(),
            "owner/demo",
            "sha",
            true,
            Instant.now()));

    executor.executeLiveRun(runId, scenario);

    assertThat(repo.completedStatus).isEqualTo(IncidentStatus.SUCCEEDED);
    assertThat(repo.savedAttempts).hasSize(1);
    assertThat(repo.savedLlmCalls).hasSize(1);
    assertThat(repo.events).anyMatch(e -> e.eventType() == IncidentEventType.COMPLETED);
    verify(workspaceManager).cloneBranch(props.demoRepoUri(), scenario.branch());
  }

  @Test
  void marksRunFailedWhenHealingDoesNotVerify(@TempDir Path tempDir) {
    WorkspaceManager workspaceManager = mock(WorkspaceManager.class);
    SandboxRunner sandboxRunner = mock(SandboxRunner.class);
    SurefireReportParser surefireParser = mock(SurefireReportParser.class);
    AgenticHealingService healingService = mock(AgenticHealingService.class);
    RecordingRepository repo = new RecordingRepository();
    IncidentEventStreamService stream = new IncidentEventStreamService(repo, new ObjectMapper());
    PipelineProperties props =
        new PipelineProperties(3, 10, 0L, "https://github.com/owner/demo.git", "owner/demo", false);

    when(workspaceManager.cloneBranch(props.demoRepoUri(), scenario.branch()))
        .thenReturn(new WorkspaceHandle(tempDir, "abc123", () -> {}));
    when(workspaceManager.readRecentDiffs(tempDir, 3)).thenReturn("");
    when(sandboxRunner.runTests(any(), any()))
        .thenReturn(new SandboxResult(1, false, 1200L, List.of("Test failure")));
    when(surefireParser.parse(tempDir))
        .thenReturn(new TestSuiteReport(5, 1, 0, 0, Set.of(), Set.of()));
    when(healingService.heal(any(), any()))
        .thenReturn(HealingResult.failed("MAX_ATTEMPTS", List.of(), List.of()));

    DefaultLiveIncidentExecutor executor =
        new DefaultLiveIncidentExecutor(
            workspaceManager,
            sandboxRunner,
            surefireParser,
            new LogDistiller(),
            healingService,
            repo,
            stream,
            props);

    UUID runId = UUID.randomUUID();
    executor.executeLiveRun(runId, scenario);

    assertThat(repo.completedStatus).isEqualTo(IncidentStatus.FAILED);
    assertThat(repo.completedFailureReason).isEqualTo("MAX_ATTEMPTS");
    assertThat(repo.events).anyMatch(e -> e.eventType() == IncidentEventType.FAILED);
  }

  @Test
  void failsLoudlyWhenDemoRepoUriNotConfigured() {
    PipelineProperties blankRepoProps = new PipelineProperties(0, 0, -1L, "", "", false);
    assertThat(blankRepoProps.maxConcurrentRuns()).isEqualTo(3);
    assertThat(blankRepoProps.maxQueuedRuns()).isEqualTo(10);
    assertThat(blankRepoProps.replayEventDelayMs()).isZero();

    DefaultLiveIncidentExecutor executor =
        new DefaultLiveIncidentExecutor(
            mock(WorkspaceManager.class),
            mock(SandboxRunner.class),
            mock(SurefireReportParser.class),
            new LogDistiller(),
            mock(AgenticHealingService.class),
            new RecordingRepository(),
            new IncidentEventStreamService(new RecordingRepository(), new ObjectMapper()),
            blankRepoProps);

    assertThatThrownBy(() -> executor.executeLiveRun(UUID.randomUUID(), scenario))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("DEMO_REPO_URI");
  }

  private static final class RecordingRepository implements IncidentRepository {

    IncidentStatus completedStatus;
    String completedFailureReason;
    final List<IncidentAttempt> savedAttempts = new ArrayList<>();
    final List<LlmCallRecord> savedLlmCalls = new ArrayList<>();
    final List<IncidentEvent> events = new ArrayList<>();

    @Override
    public IncidentRun createRun(IncidentRun run) {
      return run;
    }

    @Override
    public Optional<IncidentRun> findRunById(UUID runId) {
      return Optional.empty();
    }

    @Override
    public Optional<IncidentRun> findLatestSucceededRunByScenario(String scenarioId) {
      return Optional.empty();
    }

    @Override
    public void updateRunStatus(UUID runId, IncidentStatus status) {}

    @Override
    public void completeRun(
        UUID runId,
        IncidentStatus status,
        String failureReason,
        int attemptsUsed,
        String rootCauseSummary,
        String generatedDiff,
        String prUrl,
        int totalPromptTokens,
        int totalCompletionTokens,
        BigDecimal estimatedCostUsd,
        long durationMs,
        Instant completedAt) {
      this.completedStatus = status;
      this.completedFailureReason = failureReason;
    }

    @Override
    public void saveAttempt(IncidentAttempt attempt) {
      savedAttempts.add(attempt);
    }

    @Override
    public List<IncidentAttempt> findAttemptsByRunId(UUID runId) {
      return List.copyOf(savedAttempts);
    }

    @Override
    public void saveLlmCall(LlmCallRecord call) {
      savedLlmCalls.add(call);
    }

    @Override
    public BigDecimal sumDailyLlmCostUsd(Instant dayStartInclusive, Instant dayEndExclusive) {
      return BigDecimal.ZERO;
    }

    @Override
    public IncidentEvent appendEvent(
        UUID runId, IncidentStage stage, IncidentEventType eventType, String payloadJson) {
      IncidentEvent event =
          new IncidentEvent(runId, events.size() + 1, stage, eventType, payloadJson, Instant.now());
      events.add(event);
      return event;
    }

    @Override
    public List<IncidentEvent> findEventsAfter(UUID runId, int afterEventSeq) {
      return List.copyOf(events);
    }
  }
}
