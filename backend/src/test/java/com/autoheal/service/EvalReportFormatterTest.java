package com.autoheal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.autoheal.domain.BenchmarkAblationRow;
import com.autoheal.domain.BenchmarkFailureBucket;
import com.autoheal.domain.BenchmarkSummary;
import com.autoheal.domain.EvalCaseOutcome;
import com.autoheal.domain.EvalConfig;
import com.autoheal.domain.EvalSuiteResult;
import com.autoheal.service.EvalHarnessService.EvalRunReport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EvalReportFormatterTest {

  @Test
  void writesMarkdownAndJsonReportsWithAblationsAndTaxonomy(@TempDir Path outputDir)
      throws IOException {
    EvalReportFormatter formatter =
        new EvalReportFormatter(new ObjectMapper().registerModule(new JavaTimeModule()));

    EvalConfig fullConfig = new EvalConfig("Full (RAG + 3 attempts)", true, 3, true);
    EvalSuiteResult suiteResult =
        new EvalSuiteResult(
            fullConfig,
            List.of(
                new EvalCaseOutcome(
                    "bug-01", "NPE", true, 1, null, 82.0, 12_000L, new BigDecimal("0.0025")),
                new EvalCaseOutcome(
                    "bug-02",
                    "QUERY",
                    false,
                    3,
                    "TEST_FAILURE",
                    78.0,
                    30_000L,
                    new BigDecimal("0.0055"))),
            0.50,
            0.50,
            80.0,
            21_000L,
            30_000L,
            new BigDecimal("0.0040"),
            Map.of("TEST_FAILURE", 1));

    BenchmarkSummary summary =
        new BenchmarkSummary(
            "42",
            Instant.parse("2026-11-25T13:30:00Z"),
            2,
            0.50,
            0.50,
            21_000L,
            30_000L,
            new BigDecimal("0.0040"),
            List.of(
                new BenchmarkAblationRow(
                    "Full (RAG + 3 attempts)", 0.50, 0.50, 21_000L, new BigDecimal("0.0040"))),
            List.of(new BenchmarkFailureBucket("TEST_FAILURE", 1)));

    EvalRunReport report = new EvalRunReport(summary, List.of(suiteResult));
    formatter.writeReports(outputDir, "gemini-2.5-flash", report);

    Path mdPath = outputDir.resolve("benchmark-report.md");
    Path jsonPath = outputDir.resolve("benchmark-report.json");
    assertThat(mdPath).exists();
    assertThat(jsonPath).exists();

    String md = Files.readString(mdPath);
    assertThat(md)
        .contains("# AutoHeal Evaluation Report")
        .contains("gemini-2.5-flash")
        .contains("Full (RAG + 3 attempts)")
        .contains("TEST_FAILURE")
        .contains("NPE");

    String json = Files.readString(jsonPath);
    assertThat(json).contains("\"totalBugs\"").contains("\"failureTaxonomy\"");
  }
}
