package com.autoheal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.autoheal.domain.AttemptOutcome;
import com.autoheal.domain.AttemptRecord;
import com.autoheal.domain.BenchmarkReport;
import com.autoheal.domain.BenchmarkSummary;
import com.autoheal.domain.EvalBugCase;
import com.autoheal.domain.EvalConfig;
import com.autoheal.domain.EvalSuiteResult;
import com.autoheal.domain.HealingContext;
import com.autoheal.domain.HealingResult;
import com.autoheal.domain.LlmUsage;
import com.autoheal.domain.SandboxResult;
import com.autoheal.domain.SandboxRunner;
import com.autoheal.domain.TestCaseId;
import com.autoheal.domain.TestSuiteReport;
import com.autoheal.domain.WorkspaceHandle;
import com.autoheal.domain.WorkspaceManager;
import com.autoheal.service.EvalHarnessService.EvalRunReport;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EvalHarnessServiceTest {

  @Test
  void executesCorpusComputesMetricsEnforcesLeakageGuardAndPersistsAblations(@TempDir Path dir) {
    WorkspaceManager workspaceManager = mock(WorkspaceManager.class);
    SandboxRunner sandboxRunner = mock(SandboxRunner.class);
    SurefireReportParser surefireParser = mock(SurefireReportParser.class);
    IncidentMemoryService memoryService = mock(IncidentMemoryService.class);
    AgenticHealingService healingService = mock(AgenticHealingService.class);
    BenchmarkReportService benchmarkReportService = mock(BenchmarkReportService.class);

    when(workspaceManager.cloneBranch(anyString(), anyString()))
        .thenAnswer(inv -> new WorkspaceHandle(dir, "sha-123", () -> {}));
    when(workspaceManager.readRecentDiffs(dir, 3)).thenReturn("");
    when(sandboxRunner.runTests(any(), any()))
        .thenReturn(
            new SandboxResult(
                1,
                false,
                500L,
                List.of("[INFO] Downloading...", "[ERROR] NullPointerException at Foo.java:10")));
    when(surefireParser.parse(dir))
        .thenReturn(
            new TestSuiteReport(4, 1, 0, 0, Set.of(), Set.of(new TestCaseId("FooTest", "test"))));
    when(memoryService.retrieveSimilar(anyString(), anyString(), anyBoolean()))
        .thenReturn(List.of());

    AttemptRecord verified1 =
        new AttemptRecord(1, AttemptOutcome.VERIFIED, "diff", 0, 4, 0, 500L, "Verified");
    AttemptRecord fail1 =
        new AttemptRecord(1, AttemptOutcome.TEST_FAILURE, "diff", 1, 4, 1, 500L, "Still failing");
    AttemptRecord verified2 =
        new AttemptRecord(2, AttemptOutcome.VERIFIED, "diff", 0, 4, 0, 500L, "Verified");
    AttemptRecord compileFail =
        new AttemptRecord(1, AttemptOutcome.COMPILE_ERROR, "diff", 1, 0, 0, 400L, "Compile error");

    LlmUsage cost = new LlmUsage("gemini-2.5-flash", 500, 100, new BigDecimal("0.0030"), 200L);

    // Full config (3 attempts): bug-1 passes @1, bug-2 passes @2, bug-3 fails with COMPILE_ERROR
    // Single-shot config (1 attempt): bug-1 passes @1, bug-2 fails, bug-3 fails
    when(healingService.heal(any(HealingContext.class), eq(3), any()))
        .thenReturn(
            HealingResult.succeeded("Fix 1", "diff", List.of(verified1), List.of(cost)),
            HealingResult.succeeded("Fix 2", "diff", List.of(fail1, verified2), List.of(cost)),
            HealingResult.failed("MAX_ATTEMPTS", List.of(compileFail), List.of(cost)));
    when(healingService.heal(any(HealingContext.class), eq(1), any()))
        .thenReturn(
            HealingResult.succeeded("Fix 1", "diff", List.of(verified1), List.of(cost)),
            HealingResult.failed("MAX_ATTEMPTS", List.of(fail1), List.of(cost)),
            HealingResult.failed("MAX_ATTEMPTS", List.of(compileFail), List.of(cost)));

    when(benchmarkReportService.saveReport(anyString(), any(), any(), any()))
        .thenReturn(new BenchmarkReport(99L, "gemini-2.5-flash", "{}", "{}", Instant.now()));

    EvalHarnessService harness =
        new EvalHarnessService(
            workspaceManager,
            sandboxRunner,
            surefireParser,
            new LogDistiller(),
            memoryService,
            healingService,
            benchmarkReportService);

    List<EvalBugCase> corpus =
        List.of(
            new EvalBugCase("bug-npe-01", "NPE", "https://example/r.git", "bug-1", "Null guard"),
            new EvalBugCase("bug-off-02", "BOUNDARY", "https://example/r.git", "bug-2", "Off by 1"),
            new EvalBugCase("bug-sql-03", "QUERY", "https://example/r.git", "bug-3", "JPQL typo"));
    List<EvalConfig> configs =
        List.of(
            new EvalConfig("Full (RAG + 3 attempts)", true, 3, true),
            new EvalConfig("Single-shot (1 attempt)", true, 1, true));

    EvalRunReport report = harness.runAblationsAndPersist("gemini-2.5-flash", corpus, configs, 1);

    assertThat(report.suiteResults()).hasSize(2);
    EvalSuiteResult full = report.suiteResults().getFirst();
    assertThat(full.fixRateAt1()).isEqualTo(1.0 / 3.0);
    assertThat(full.fixRateAtMax()).isEqualTo(2.0 / 3.0);
    assertThat(full.meanCostUsd()).isEqualByComparingTo("0.003000");
    assertThat(full.failureTaxonomy()).containsEntry("COMPILE_ERROR", 1);

    BenchmarkSummary summary = report.summary();
    assertThat(summary.totalBugs()).isEqualTo(3);
    assertThat(summary.ablations()).hasSize(2);
    assertThat(summary.failureTaxonomy()).hasSize(1);

    // Eval leakage guard: bug IDs must be passed as originBugId to IncidentMemoryService
    verify(memoryService, org.mockito.Mockito.atLeastOnce())
        .retrieveSimilar(anyString(), eq("bug-npe-01"), eq(true));
    verify(benchmarkReportService).saveReport(eq("gemini-2.5-flash"), any(), any(), any());
  }
}
