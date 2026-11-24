package com.autoheal.service;

import com.autoheal.domain.AttemptOutcome;
import com.autoheal.domain.AttemptRecord;
import com.autoheal.domain.HealingResult;
import com.autoheal.domain.LlmUsage;
import com.autoheal.domain.TestSuiteReport;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class PullRequestBodyFormatter {

  public String format(
      String scenarioId,
      TestSuiteReport baseline,
      HealingResult result,
      List<String> modifiedFiles,
      List<String> createdFiles) {
    Objects.requireNonNull(scenarioId, "scenarioId must not be null");
    Objects.requireNonNull(baseline, "baseline must not be null");
    Objects.requireNonNull(result, "result must not be null");
    Objects.requireNonNull(modifiedFiles, "modifiedFiles must not be null");
    Objects.requireNonNull(createdFiles, "createdFiles must not be null");

    AttemptRecord verified =
        result.attempts().stream()
            .filter(a -> a.outcome() == AttemptOutcome.VERIFIED)
            .findFirst()
            .orElseThrow(
                () -> new IllegalArgumentException("HealingResult has no VERIFIED attempt"));

    long totalLlmLatencyMs = result.llmCalls().stream().mapToLong(LlmUsage::latencyMs).sum();
    String oracleList =
        baseline.failingTests().stream()
            .map(tc -> tc.className() + "#" + tc.methodName())
            .sorted()
            .collect(Collectors.joining(", "));

    StringBuilder sb = new StringBuilder();
    sb.append("## Root Cause Analysis\n");
    sb.append(result.rootCauseSummary()).append("\n\n");

    sb.append("## Summary of Changes\n");
    sb.append("- Scenario: `").append(scenarioId).append("`\n");
    for (String file : modifiedFiles) {
      sb.append("- Modified: `").append(file).append("`\n");
    }
    for (String file : createdFiles) {
      sb.append("- Added: `").append(file).append("`\n");
    }
    sb.append("\n");

    sb.append("## Verification Evidence\n");
    sb.append("- Exit code: `").append(verified.sandboxExitCode()).append("`\n");
    sb.append("- Originally failing oracle test(s) now passing: `")
        .append(oracleList)
        .append("`\n");
    sb.append("- Executed tests (before → after): `")
        .append(baseline.executedTests())
        .append("` → `")
        .append(verified.testsRun())
        .append("`\n");
    sb.append("- Sandbox execution time: `")
        .append(verified.sandboxDurationMs())
        .append(" ms`\n\n");

    sb.append("## Agent Metrics\n");
    sb.append("- Attempts used: `").append(result.attempts().size()).append("`\n");
    sb.append("- LLM calls: `").append(result.llmCalls().size()).append("`\n");
    sb.append("- Total LLM latency: `").append(totalLlmLatencyMs).append(" ms`\n");
    sb.append("- Tokens: `")
        .append(result.totalPromptTokens())
        .append("` prompt / `")
        .append(result.totalCompletionTokens())
        .append("` completion\n");
    sb.append("- Estimated cost: `$").append(result.totalCostUsd().toPlainString()).append("`\n");
    return sb.toString();
  }
}
