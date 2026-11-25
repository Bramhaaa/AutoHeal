package com.autoheal.service;

import com.autoheal.domain.AttemptRecord;
import com.autoheal.domain.BenchmarkAblationRow;
import com.autoheal.domain.BenchmarkFailureBucket;
import com.autoheal.domain.BenchmarkReport;
import com.autoheal.domain.BenchmarkSummary;
import com.autoheal.domain.DistilledLog;
import com.autoheal.domain.EvalBugCase;
import com.autoheal.domain.EvalCaseOutcome;
import com.autoheal.domain.EvalConfig;
import com.autoheal.domain.EvalSuiteResult;
import com.autoheal.domain.HealingContext;
import com.autoheal.domain.HealingResult;
import com.autoheal.domain.IncidentMemoryEntry;
import com.autoheal.domain.SandboxRequest;
import com.autoheal.domain.SandboxResult;
import com.autoheal.domain.SandboxRunner;
import com.autoheal.domain.TestSuiteReport;
import com.autoheal.domain.WorkspaceHandle;
import com.autoheal.domain.WorkspaceManager;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class EvalHarnessService {

  private final WorkspaceManager workspaceManager;
  private final SandboxRunner sandboxRunner;
  private final SurefireReportParser surefireParser;
  private final LogDistiller logDistiller;
  private final IncidentMemoryService incidentMemoryService;
  private final AgenticHealingService healingService;
  private final BenchmarkReportService benchmarkReportService;

  public EvalHarnessService(
      WorkspaceManager workspaceManager,
      SandboxRunner sandboxRunner,
      SurefireReportParser surefireParser,
      LogDistiller logDistiller,
      IncidentMemoryService incidentMemoryService,
      AgenticHealingService healingService,
      BenchmarkReportService benchmarkReportService) {
    this.workspaceManager = Objects.requireNonNull(workspaceManager, "workspaceManager");
    this.sandboxRunner = Objects.requireNonNull(sandboxRunner, "sandboxRunner");
    this.surefireParser = Objects.requireNonNull(surefireParser, "surefireParser");
    this.logDistiller = Objects.requireNonNull(logDistiller, "logDistiller");
    this.incidentMemoryService =
        Objects.requireNonNull(incidentMemoryService, "incidentMemoryService");
    this.healingService = Objects.requireNonNull(healingService, "healingService");
    this.benchmarkReportService =
        Objects.requireNonNull(benchmarkReportService, "benchmarkReportService");
  }

  public EvalRunReport runAblationsAndPersist(
      String model, List<EvalBugCase> corpus, List<EvalConfig> configs, int repeatCount) {
    Objects.requireNonNull(model, "model must not be null");
    if (corpus == null || corpus.isEmpty()) {
      throw new IllegalArgumentException("corpus must not be empty");
    }
    if (configs == null || configs.isEmpty()) {
      throw new IllegalArgumentException("configs must not be empty");
    }
    if (repeatCount < 1) {
      throw new IllegalArgumentException("repeatCount must be >= 1");
    }

    List<EvalSuiteResult> suiteResults = new ArrayList<>();
    for (EvalConfig config : configs) {
      suiteResults.add(runSuite(corpus, config, repeatCount));
    }

    EvalSuiteResult primary = suiteResults.getFirst();
    Instant now = Instant.now();

    List<BenchmarkAblationRow> ablationRows =
        suiteResults.stream()
            .map(
                s ->
                    new BenchmarkAblationRow(
                        s.config().label(),
                        s.fixRateAt1(),
                        s.fixRateAtMax(),
                        s.medianDurationMs(),
                        s.meanCostUsd()))
            .toList();

    List<BenchmarkFailureBucket> failureBuckets =
        primary.failureTaxonomy().entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .map(e -> new BenchmarkFailureBucket(e.getKey(), e.getValue()))
            .toList();

    BenchmarkSummary draftSummary =
        new BenchmarkSummary(
            null,
            now,
            corpus.size(),
            primary.fixRateAt1(),
            primary.fixRateAtMax(),
            primary.medianDurationMs(),
            primary.p90DurationMs(),
            primary.meanCostUsd(),
            ablationRows,
            failureBuckets);

    BenchmarkReport saved =
        benchmarkReportService.saveReport(
            model,
            Map.of(
                "rag", primary.config().ragEnabled(),
                "maxAttempts", primary.config().maxAttempts(),
                "distillLogs", primary.config().distillLogs(),
                "repeatCount", repeatCount),
            draftSummary,
            now);

    BenchmarkSummary persistedSummary =
        new BenchmarkSummary(
            String.valueOf(saved.id()),
            now,
            draftSummary.totalBugs(),
            draftSummary.passAt1Rate(),
            draftSummary.passAt3Rate(),
            draftSummary.medianDurationMs(),
            draftSummary.p90DurationMs(),
            draftSummary.meanCostUsd(),
            draftSummary.ablations(),
            draftSummary.failureTaxonomy());

    return new EvalRunReport(persistedSummary, suiteResults);
  }

  public EvalSuiteResult runSuite(List<EvalBugCase> corpus, EvalConfig config, int repeatCount) {
    List<EvalCaseOutcome> outcomes = new ArrayList<>();
    for (int run = 0; run < repeatCount; run++) {
      for (EvalBugCase bug : corpus) {
        outcomes.add(runSingleCase(bug, config));
      }
    }
    return aggregate(config, outcomes);
  }

  private EvalCaseOutcome runSingleCase(EvalBugCase bug, EvalConfig config) {
    long startedMs = System.currentTimeMillis();
    try (WorkspaceHandle workspace = workspaceManager.cloneBranch(bug.repoUri(), bug.branch())) {
      SandboxResult baselineSandbox =
          sandboxRunner.runTests(
              new SandboxRequest(workspace.directory(), SandboxRequest.DEFAULT_TIMEOUT),
              line -> {});
      TestSuiteReport baselineReport = surefireParser.parse(workspace.directory());
      String rawLog = String.join("\n", baselineSandbox.outputLines());

      DistilledLog log =
          config.distillLogs()
              ? logDistiller.distill(rawLog)
              : new DistilledLog(rawLog, List.of(), List.of(), rawLog.length(), rawLog.length());
      String recentDiffs = workspaceManager.readRecentDiffs(workspace.directory(), 3);
      List<IncidentMemoryEntry> matches =
          incidentMemoryService.retrieveSimilar(log.summary(), bug.id(), config.ragEnabled());

      HealingContext context =
          new HealingContext(workspace.directory(), log, baselineReport, recentDiffs, matches);
      HealingResult result = healingService.heal(context, config.maxAttempts(), line -> {});
      long durationMs = Math.max(0L, System.currentTimeMillis() - startedMs);

      String failureReason = result.succeeded() ? null : classifyFailure(result);
      return new EvalCaseOutcome(
          bug.id(),
          bug.category(),
          result.succeeded(),
          result.attempts().size(),
          failureReason,
          log.reductionPercent(),
          durationMs,
          result.totalCostUsd());
    }
  }

  private static String classifyFailure(HealingResult result) {
    if (!result.attempts().isEmpty()) {
      AttemptRecord last = result.attempts().getLast();
      return last.outcome().name();
    }
    return result.failureReason() == null ? "MAX_ATTEMPTS" : result.failureReason();
  }

  private static EvalSuiteResult aggregate(EvalConfig config, List<EvalCaseOutcome> outcomes) {
    int total = outcomes.size();
    long passedAt1 = outcomes.stream().filter(o -> o.verified() && o.attemptsUsed() == 1).count();
    long passedAtMax = outcomes.stream().filter(EvalCaseOutcome::verified).count();

    double fixRateAt1 = (double) passedAt1 / total;
    double fixRateAtMax = (double) passedAtMax / total;
    double meanReduction =
        outcomes.stream().mapToDouble(EvalCaseOutcome::logReductionPercent).average().orElse(0.0);

    List<Long> sortedDurations =
        outcomes.stream()
            .map(EvalCaseOutcome::durationMs)
            .sorted(Comparator.naturalOrder())
            .toList();
    long medianDurationMs = percentile(sortedDurations, 0.50);
    long p90DurationMs = percentile(sortedDurations, 0.90);

    BigDecimal totalCost =
        outcomes.stream().map(EvalCaseOutcome::costUsd).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal meanCostUsd = totalCost.divide(BigDecimal.valueOf(total), 6, RoundingMode.HALF_UP);

    Map<String, Integer> taxonomy = new LinkedHashMap<>();
    for (EvalCaseOutcome outcome : outcomes) {
      if (!outcome.verified() && outcome.failureReason() != null) {
        taxonomy.merge(outcome.failureReason(), 1, Integer::sum);
      }
    }

    return new EvalSuiteResult(
        config,
        outcomes,
        fixRateAt1,
        fixRateAtMax,
        meanReduction,
        medianDurationMs,
        p90DurationMs,
        meanCostUsd,
        taxonomy);
  }

  private static long percentile(List<Long> sorted, double quantile) {
    if (sorted.isEmpty()) {
      return 0L;
    }
    int idx = (int) Math.ceil(quantile * sorted.size()) - 1;
    int clamped = Math.max(0, Math.min(idx, sorted.size() - 1));
    return sorted.get(clamped);
  }

  public record EvalRunReport(BenchmarkSummary summary, List<EvalSuiteResult> suiteResults) {
    public EvalRunReport {
      Objects.requireNonNull(summary, "summary must not be null");
      suiteResults =
          List.copyOf(Objects.requireNonNull(suiteResults, "suiteResults must not be null"));
    }
  }
}
