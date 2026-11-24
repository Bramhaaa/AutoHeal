package com.autoheal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.autoheal.domain.AttemptOutcome;
import com.autoheal.domain.AttemptRecord;
import com.autoheal.domain.HealingResult;
import com.autoheal.domain.LlmUsage;
import com.autoheal.domain.TestCaseId;
import com.autoheal.domain.TestSuiteReport;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PullRequestBodyFormatterTest {

  private final PullRequestBodyFormatter formatter = new PullRequestBodyFormatter();

  @Test
  void formatsAllRequiredFr52SectionsWithEvidenceAndMetrics() {
    TestCaseId oracle = new TestCaseId("com.demo.PaymentServiceTest", "handlesNullDiscount");
    TestSuiteReport baseline = new TestSuiteReport(4, 0, 1, 0, Set.of(), Set.of(oracle));
    AttemptRecord verifiedAttempt =
        new AttemptRecord(
            1,
            AttemptOutcome.VERIFIED,
            "--- a/src/main/java/com/demo/PaymentService.java\n+++ b/src/main/java/com/demo/PaymentService.java\n",
            0,
            5,
            0,
            8700L,
            "Verified");
    LlmUsage usage = new LlmUsage("gemini-2.5-flash", 1200, 350, new BigDecimal("0.000390"), 950L);
    HealingResult result =
        HealingResult.succeeded(
            "Null discount payload was dereferenced without null check.",
            verifiedAttempt.unifiedDiff(),
            List.of(verifiedAttempt),
            List.of(usage));

    String body =
        formatter.format(
            "payment-npe-01",
            baseline,
            result,
            List.of("src/main/java/com/demo/PaymentService.java"),
            List.of("src/test/java/com/demo/PaymentRegressionTest.java"));

    assertThat(body)
        .contains("## Root Cause Analysis")
        .contains("Null discount payload was dereferenced without null check.")
        .contains("## Summary of Changes")
        .contains("src/main/java/com/demo/PaymentService.java")
        .contains("src/test/java/com/demo/PaymentRegressionTest.java")
        .contains("## Verification Evidence")
        .contains("Exit code: `0`")
        .contains("com.demo.PaymentServiceTest#handlesNullDiscount")
        .contains("4` → `5")
        .contains("8700 ms")
        .contains("## Agent Metrics")
        .contains("Attempts used: `1`")
        .contains("1200` prompt / `350` completion")
        .contains("$0.000390");
  }
}
