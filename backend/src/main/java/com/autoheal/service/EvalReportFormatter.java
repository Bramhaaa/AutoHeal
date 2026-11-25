package com.autoheal.service;

import com.autoheal.domain.BenchmarkFailureBucket;
import com.autoheal.domain.EvalCaseOutcome;
import com.autoheal.domain.EvalSuiteResult;
import com.autoheal.service.EvalHarnessService.EvalRunReport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class EvalReportFormatter {

  private final ObjectMapper objectMapper;

  public EvalReportFormatter(ObjectMapper objectMapper) {
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
  }

  public void writeReports(Path outputDir, String model, EvalRunReport report) {
    Objects.requireNonNull(outputDir, "outputDir must not be null");
    Objects.requireNonNull(model, "model must not be null");
    Objects.requireNonNull(report, "report must not be null");

    try {
      Files.createDirectories(outputDir);
      String markdown = toMarkdown(model, report);
      Files.writeString(outputDir.resolve("benchmark-report.md"), markdown, StandardCharsets.UTF_8);

      String json =
          objectMapper
              .copy()
              .enable(SerializationFeature.INDENT_OUTPUT)
              .writeValueAsString(report.summary());
      Files.writeString(outputDir.resolve("benchmark-report.json"), json, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to write evaluation reports to " + outputDir, e);
    }
  }

  public String toMarkdown(String model, EvalRunReport report) {
    var summary = report.summary();
    StringBuilder sb = new StringBuilder();
    sb.append("# AutoHeal Evaluation Report\n\n");
    sb.append("- **Model:** `").append(model).append("`\n");
    sb.append("- **Corpus Size:** ").append(summary.totalBugs()).append(" bugs\n");
    sb.append("- **Generated At:** ").append(summary.generatedAt()).append("\n\n");

    sb.append("## Ablation Results\n\n");
    sb.append(
        "| Configuration | Fix Rate @1 | Fix Rate @Max | Median Time (s) | Mean Cost (USD) |\n");
    sb.append("| --- | ---: | ---: | ---: | ---: |\n");
    for (var row : summary.ablations()) {
      sb.append(
          String.format(
              Locale.ROOT,
              "| %s | %.1f%% | %.1f%% | %.1f | $%.4f |\n",
              row.label(),
              row.passAt1Rate() * 100.0,
              row.passAt3Rate() * 100.0,
              row.medianDurationMs() / 1000.0,
              row.meanCostUsd()));
    }

    sb.append("\n## Failure Taxonomy (Primary Configuration)\n\n");
    if (summary.failureTaxonomy().isEmpty()) {
      sb.append("_No failures recorded._\n");
    } else {
      sb.append("| Failure Reason | Count |\n");
      sb.append("| --- | ---: |\n");
      for (BenchmarkFailureBucket bucket : summary.failureTaxonomy()) {
        sb.append("| `")
            .append(bucket.reason())
            .append("` | ")
            .append(bucket.count())
            .append(" |\n");
      }
    }

    if (!report.suiteResults().isEmpty()) {
      EvalSuiteResult primary = report.suiteResults().getFirst();
      sb.append("\n## Per-Category Breakdown\n\n");
      sb.append("| Category | Verified | Total | Fix Rate |\n");
      sb.append("| --- | ---: | ---: | ---: |\n");
      Map<String, List<EvalCaseOutcome>> byCategory = groupByCategory(primary.outcomes());
      for (Map.Entry<String, List<EvalCaseOutcome>> entry : byCategory.entrySet()) {
        long verified = entry.getValue().stream().filter(EvalCaseOutcome::verified).count();
        int total = entry.getValue().size();
        sb.append(
            String.format(
                Locale.ROOT,
                "| %s | %d | %d | %.1f%% |\n",
                entry.getKey(),
                verified,
                total,
                (verified * 100.0) / total));
      }
    }
    return sb.toString();
  }

  private static Map<String, List<EvalCaseOutcome>> groupByCategory(
      List<EvalCaseOutcome> outcomes) {
    Map<String, List<EvalCaseOutcome>> grouped = new LinkedHashMap<>();
    for (EvalCaseOutcome outcome : outcomes) {
      grouped.computeIfAbsent(outcome.category(), k -> new java.util.ArrayList<>()).add(outcome);
    }
    return grouped;
  }
}
