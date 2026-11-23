package com.autoheal.domain;

import java.util.Set;

public record TestSuiteReport(
    int totalTests,
    int failures,
    int errors,
    int skipped,
    Set<TestCaseId> passingTests,
    Set<TestCaseId> failingTests) {

  public TestSuiteReport {
    passingTests = Set.copyOf(passingTests);
    failingTests = Set.copyOf(failingTests);
  }

  public static TestSuiteReport empty() {
    return new TestSuiteReport(0, 0, 0, 0, Set.of(), Set.of());
  }

  public int executedTests() {
    return totalTests - skipped;
  }
}
