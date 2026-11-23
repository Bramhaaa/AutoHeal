package com.autoheal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autoheal.domain.TestCaseId;
import com.autoheal.domain.TestSuiteReport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SurefireReportParserTest {

  private final SurefireReportParser parser = new SurefireReportParser();

  @Test
  void parsesPassingFailingErrorAndSkippedTestCases(@TempDir Path workspace) throws IOException {
    Path reportsDir = workspace.resolve("target/surefire-reports");
    Files.createDirectories(reportsDir);
    Files.writeString(
        reportsDir.resolve("TEST-com.example.PaymentServiceTest.xml"),
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <testsuite name="com.example.PaymentServiceTest" tests="4" failures="1" errors="1" skipped="1">
          <testcase name="appliesDiscount" classname="com.example.PaymentServiceTest" time="0.01"/>
          <testcase name="handlesNullDiscount" classname="com.example.PaymentServiceTest" time="0.01">
            <error type="java.lang.NullPointerException">Cannot invoke percentage()</error>
          </testcase>
          <testcase name="roundsCorrectly" classname="com.example.PaymentServiceTest" time="0.01">
            <failure message="expected 10.00"/>
          </testcase>
          <testcase name="ignoredCase" classname="com.example.PaymentServiceTest" time="0.0">
            <skipped/>
          </testcase>
        </testsuite>
        """);

    TestSuiteReport report = parser.parse(workspace);

    assertThat(report.totalTests()).isEqualTo(4);
    assertThat(report.failures()).isEqualTo(1);
    assertThat(report.errors()).isEqualTo(1);
    assertThat(report.skipped()).isEqualTo(1);
    assertThat(report.executedTests()).isEqualTo(3);
    TestCaseId expectedPassing =
        new TestCaseId("com.example.PaymentServiceTest", "appliesDiscount");
    assertThat(expectedPassing.qualifiedName())
        .isEqualTo("com.example.PaymentServiceTest#appliesDiscount");
    assertThat(report.passingTests()).containsExactly(expectedPassing);
    assertThat(report.failingTests())
        .containsExactlyInAnyOrder(
            new TestCaseId("com.example.PaymentServiceTest", "handlesNullDiscount"),
            new TestCaseId("com.example.PaymentServiceTest", "roundsCorrectly"));
  }

  @Test
  void returnsEmptyReportWhenReportsDirectoryDoesNotExist(@TempDir Path workspace) {
    TestSuiteReport report = parser.parse(workspace);

    assertThat(report).isEqualTo(TestSuiteReport.empty());
  }

  @Test
  void rejectsXmlWithExternalEntityDtd(@TempDir Path workspace) throws IOException {
    Path reportsDir = workspace.resolve("target/surefire-reports");
    Files.createDirectories(reportsDir);
    Files.writeString(
        reportsDir.resolve("TEST-xxe.xml"),
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE foo [ <!ENTITY xxe SYSTEM "file:///etc/passwd"> ]>
        <testsuite name="xxe" tests="1" failures="0" errors="0" skipped="0">
          <testcase name="&xxe;" classname="xxe" time="0.0"/>
        </testsuite>
        """);

    assertThatThrownBy(() -> parser.parse(workspace))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Failed to parse Surefire report");
  }
}
