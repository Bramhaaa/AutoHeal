package com.autoheal.service;

import com.autoheal.domain.AttemptRecord;
import com.autoheal.domain.DistilledLog;
import com.autoheal.domain.HealingContext;
import com.autoheal.domain.HealingResult;
import com.autoheal.domain.IncidentAttempt;
import com.autoheal.domain.IncidentEventType;
import com.autoheal.domain.IncidentRepository;
import com.autoheal.domain.IncidentStage;
import com.autoheal.domain.IncidentStatus;
import com.autoheal.domain.LiveIncidentExecutor;
import com.autoheal.domain.LlmCallRecord;
import com.autoheal.domain.LlmUsage;
import com.autoheal.domain.SandboxRequest;
import com.autoheal.domain.SandboxResult;
import com.autoheal.domain.SandboxRunner;
import com.autoheal.domain.ScenarioDefinition;
import com.autoheal.domain.TestSuiteReport;
import com.autoheal.domain.WorkspaceHandle;
import com.autoheal.domain.WorkspaceManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class DefaultLiveIncidentExecutor implements LiveIncidentExecutor {

  private final WorkspaceManager workspaceManager;
  private final SandboxRunner sandboxRunner;
  private final SurefireReportParser surefireParser;
  private final LogDistiller logDistiller;
  private final AgenticHealingService healingService;
  private final IncidentRepository incidentRepository;
  private final IncidentEventStreamService eventStreamService;
  private final PipelineProperties properties;

  public DefaultLiveIncidentExecutor(
      WorkspaceManager workspaceManager,
      SandboxRunner sandboxRunner,
      SurefireReportParser surefireParser,
      LogDistiller logDistiller,
      AgenticHealingService healingService,
      IncidentRepository incidentRepository,
      IncidentEventStreamService eventStreamService,
      PipelineProperties properties) {
    this.workspaceManager = Objects.requireNonNull(workspaceManager, "workspaceManager");
    this.sandboxRunner = Objects.requireNonNull(sandboxRunner, "sandboxRunner");
    this.surefireParser = Objects.requireNonNull(surefireParser, "surefireParser");
    this.logDistiller = Objects.requireNonNull(logDistiller, "logDistiller");
    this.healingService = Objects.requireNonNull(healingService, "healingService");
    this.incidentRepository = Objects.requireNonNull(incidentRepository, "incidentRepository");
    this.eventStreamService = Objects.requireNonNull(eventStreamService, "eventStreamService");
    this.properties = Objects.requireNonNull(properties, "properties");
  }

  @Override
  public void executeLiveRun(UUID runId, ScenarioDefinition scenario) {
    Objects.requireNonNull(runId, "runId must not be null");
    Objects.requireNonNull(scenario, "scenario must not be null");
    if (properties.demoRepoUri().isBlank()) {
      throw new IllegalStateException(
          "autoheal.pipeline.demo-repo-uri (DEMO_REPO_URI) must be configured for LIVE runs");
    }

    long startedMs = System.currentTimeMillis();
    incidentRepository.updateRunStatus(runId, IncidentStatus.PREPARING);
    eventStreamService.publish(
        runId,
        IncidentStage.INCIDENT_RECEIVED,
        IncidentEventType.STAGE_UPDATE,
        Map.of("scenarioId", scenario.id(), "branch", scenario.branch()));

    try (WorkspaceHandle workspace =
        workspaceManager.cloneBranch(properties.demoRepoUri(), scenario.branch())) {

      SandboxResult baselineSandbox =
          sandboxRunner.runTests(
              new SandboxRequest(workspace.directory(), SandboxRequest.DEFAULT_TIMEOUT),
              line ->
                  eventStreamService.publish(
                      runId,
                      IncidentStage.INCIDENT_RECEIVED,
                      IncidentEventType.LOG_LINE,
                      Map.of("message", line, "phase", "baseline")));

      TestSuiteReport baselineReport = surefireParser.parse(workspace.directory());
      String rawLog = String.join("\n", baselineSandbox.outputLines());

      incidentRepository.updateRunStatus(runId, IncidentStatus.DISTILLING);
      DistilledLog distilled = logDistiller.distill(rawLog);
      String recentDiffs = workspaceManager.readRecentDiffs(workspace.directory(), 3);
      eventStreamService.publish(
          runId,
          IncidentStage.LOG_DISTILLED,
          IncidentEventType.STAGE_UPDATE,
          Map.of("reductionPercent", distilled.reductionPercent()));

      incidentRepository.updateRunStatus(runId, IncidentStatus.PATCHING);
      eventStreamService.publish(
          runId,
          IncidentStage.AGENT_PATCHING,
          IncidentEventType.STAGE_UPDATE,
          Map.of("maxAttempts", AgenticHealingService.MAX_ATTEMPTS));

      HealingContext context =
          new HealingContext(workspace.directory(), distilled, baselineReport, recentDiffs);
      HealingResult result =
          healingService.heal(
              context,
              line ->
                  eventStreamService.publish(
                      runId,
                      IncidentStage.SANDBOX_VERIFY,
                      IncidentEventType.LOG_LINE,
                      Map.of("message", line, "phase", "verify")));

      persistMetricsAndAttempts(runId, result);
      long durationMs = Math.max(0L, System.currentTimeMillis() - startedMs);

      if (result.succeeded()) {
        incidentRepository.completeRun(
            runId,
            IncidentStatus.SUCCEEDED,
            null,
            result.attempts().size(),
            result.rootCauseSummary(),
            result.unifiedDiff(),
            null,
            result.totalPromptTokens(),
            result.totalCompletionTokens(),
            result.totalCostUsd(),
            durationMs,
            Instant.now());
        eventStreamService.publish(
            runId,
            IncidentStage.PR_OPENED,
            IncidentEventType.COMPLETED,
            Map.of(
                "attemptsUsed", result.attempts().size(),
                "rootCauseSummary", result.rootCauseSummary(),
                "costUsd", result.totalCostUsd().toPlainString()));
      } else {
        String reason = result.failureReason() == null ? "MAX_ATTEMPTS" : result.failureReason();
        incidentRepository.completeRun(
            runId,
            IncidentStatus.FAILED,
            reason,
            result.attempts().size(),
            null,
            null,
            null,
            result.totalPromptTokens(),
            result.totalCompletionTokens(),
            result.totalCostUsd(),
            durationMs,
            Instant.now());
        eventStreamService.publish(
            runId,
            IncidentStage.SANDBOX_VERIFY,
            IncidentEventType.FAILED,
            Map.of("reason", reason, "attemptsUsed", result.attempts().size()));
      }
    }
  }

  private void persistMetricsAndAttempts(UUID runId, HealingResult result) {
    Instant now = Instant.now();
    for (AttemptRecord attempt : result.attempts()) {
      incidentRepository.saveAttempt(
          new IncidentAttempt(
              runId,
              attempt.attemptNo(),
              attempt.outcome(),
              attempt.unifiedDiff(),
              attempt.sandboxExitCode(),
              attempt.testsRun(),
              attempt.testsFailed(),
              attempt.sandboxDurationMs(),
              now));
      eventStreamService.publish(
          runId,
          IncidentStage.SANDBOX_VERIFY,
          IncidentEventType.ATTEMPT_RESULT,
          Map.of(
              "attempt", attempt.attemptNo(),
              "outcome", attempt.outcome().name(),
              "testsRun", attempt.testsRun(),
              "testsFailed", attempt.testsFailed()));
    }
    for (LlmUsage usage : result.llmCalls()) {
      incidentRepository.saveLlmCall(
          new LlmCallRecord(
              runId,
              null,
              usage.model(),
              usage.promptTokens(),
              usage.completionTokens(),
              usage.estimatedCostUsd() == null ? BigDecimal.ZERO : usage.estimatedCostUsd(),
              usage.latencyMs(),
              now));
    }
  }
}
