package com.autoheal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.autoheal.domain.NewTestFile;
import com.autoheal.domain.PatchProposal;
import com.autoheal.domain.PolicyValidationResult;
import com.autoheal.domain.SearchReplaceEdit;
import com.autoheal.domain.TestCaseId;
import com.autoheal.domain.TestSuiteReport;
import com.autoheal.domain.VerificationResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestIntegrityPolicyTest {

  @TempDir Path tempDir;
  private Path workspace;
  private TestIntegrityPolicy policy;

  @BeforeEach
  void setUp() throws IOException {
    workspace = Files.createDirectories(tempDir.resolve("workspace"));
    Files.createDirectories(workspace.resolve("src/main/java/com/demo"));
    Files.createDirectories(workspace.resolve("src/test/java/com/demo"));
    Files.createDirectories(workspace.resolve(".mvn"));
    Files.createDirectories(workspace.resolve(".github/workflows"));

    Files.writeString(
        workspace.resolve("src/main/java/com/demo/PaymentService.java"),
        "class PaymentService {}\n");
    Files.writeString(
        workspace.resolve("src/test/java/com/demo/PaymentServiceTest.java"),
        "class PaymentServiceTest {}\n");
    Files.writeString(workspace.resolve("pom.xml"), "<project/>\n");
    Files.writeString(workspace.resolve(".mvn/jvm.config"), "-Xmx256m\n");
    Files.writeString(workspace.resolve("mvnw"), "#!/bin/sh\n");
    Files.writeString(workspace.resolve(".github/workflows/ci.yml"), "name: CI\n");

    policy = new TestIntegrityPolicy(new WorkspacePathResolver());
  }

  @Test
  void allowsMainSourceEditAndNewRegressionTest() {
    PatchProposal proposal =
        new PatchProposal(
            List.of(
                new SearchReplaceEdit(
                    "src/main/java/com/demo/PaymentService.java",
                    "class PaymentService {}",
                    "class PaymentService { int ok() { return 1; } }")),
            List.of(
                new NewTestFile(
                    "src/test/java/com/demo/PaymentServiceRegressionTest.java",
                    "class PaymentServiceRegressionTest { @Test void works() {} }")),
            "Handle null discount");

    PolicyValidationResult result = policy.validatePatch(workspace, proposal);

    assertThat(result.allowed()).isTrue();
    assertThat(result.violations()).isEmpty();
  }

  @Test
  void rejectsEmptyEdits() {
    PatchProposal proposal = new PatchProposal(List.of(), List.of(), "No-op");

    PolicyValidationResult result = policy.validatePatch(workspace, proposal);

    assertThat(result.allowed()).isFalse();
    assertThat(result.violations()).anyMatch(v -> v.contains("at least one"));
  }

  @Test
  void rejectsModifyingExistingTestFile() {
    PatchProposal proposal =
        new PatchProposal(
            List.of(
                new SearchReplaceEdit(
                    "src/test/java/com/demo/PaymentServiceTest.java", "class", "// class")),
            List.of(),
            "Weaken test");

    PolicyValidationResult result = policy.validatePatch(workspace, proposal);

    assertThat(result.allowed()).isFalse();
    assertThat(result.violations()).anyMatch(v -> v.contains("src/main/"));
  }

  @Test
  void rejectsModifyingPomMvnWrapperAndCiConfig() {
    List<String> forbiddenPaths =
        List.of("pom.xml", ".mvn/jvm.config", "mvnw", ".github/workflows/ci.yml");

    for (String path : forbiddenPaths) {
      PatchProposal proposal =
          new PatchProposal(
              List.of(new SearchReplaceEdit(path, "a", "b")), List.of(), "Tamper build");

      PolicyValidationResult result = policy.validatePatch(workspace, proposal);

      assertThat(result.allowed()).as("Should reject editing %s", path).isFalse();
    }
  }

  @Test
  void rejectsPathTraversalInEditsAndNewTests() {
    PatchProposal proposal =
        new PatchProposal(
            List.of(new SearchReplaceEdit("../outside.txt", "a", "b")),
            List.of(new NewTestFile("../../evil.java", "class Evil {}")),
            "Escape workspace");

    PolicyValidationResult result = policy.validatePatch(workspace, proposal);

    assertThat(result.allowed()).isFalse();
    assertThat(result.violations()).hasSizeGreaterThanOrEqualTo(2);
  }

  @Test
  void rejectsNewTestFileOverwritingExistingTestOrOutsideSrcTest() {
    PatchProposal overwriteExisting =
        new PatchProposal(
            List.of(
                new SearchReplaceEdit(
                    "src/main/java/com/demo/PaymentService.java", "class", "public class")),
            List.of(
                new NewTestFile(
                    "src/test/java/com/demo/PaymentServiceTest.java", "class Overwritten {}")),
            "Overwrite existing test");

    assertThat(policy.validatePatch(workspace, overwriteExisting).allowed()).isFalse();

    PatchProposal outsideTestDir =
        new PatchProposal(
            List.of(
                new SearchReplaceEdit(
                    "src/main/java/com/demo/PaymentService.java", "class", "public class")),
            List.of(new NewTestFile("src/main/java/com/demo/Sneaky.java", "class Sneaky {}")),
            "New file outside src/test");

    assertThat(policy.validatePatch(workspace, outsideTestDir).allowed()).isFalse();
  }

  @Test
  void rejectsSkipMechanismsInNewTests() {
    List<String> skipPayloads =
        List.of(
            "@Disabled class T {}",
            "@org.junit.jupiter.api.Disabled(\"later\") void t() {}",
            "@Ignore void t() {}",
            "void t() { assumeTrue(false); }",
            "void t() { Assumptions.assumeTrue(false, \"skip\"); }",
            "void t() { assumeFalse(true); }",
            "void t() { Assumptions.abort(\"skip\"); }");

    for (String content : skipPayloads) {
      PatchProposal proposal =
          new PatchProposal(
              List.of(
                  new SearchReplaceEdit(
                      "src/main/java/com/demo/PaymentService.java", "class", "public class")),
              List.of(new NewTestFile("src/test/java/com/demo/NewTest.java", content)),
              "Skip test");

      PolicyValidationResult result = policy.validatePatch(workspace, proposal);

      assertThat(result.allowed()).as("Should reject skip payload: %s", content).isFalse();
    }
  }

  @Test
  void verifyOutcomePassesWhenExitZeroOraclePassesAndCountMaintained() {
    TestCaseId oracle = new TestCaseId("com.demo.PaymentServiceTest", "handlesNullDiscount");
    TestCaseId other = new TestCaseId("com.demo.PaymentServiceTest", "appliesValidDiscount");

    TestSuiteReport baseline = new TestSuiteReport(2, 1, 0, 0, Set.of(other), Set.of(oracle));
    TestSuiteReport afterPatch = new TestSuiteReport(3, 0, 0, 0, Set.of(other, oracle), Set.of());

    VerificationResult result = policy.verifyOutcome(0, baseline, afterPatch);

    assertThat(result.verified()).isTrue();
    assertThat(result.reasons()).isEmpty();
  }

  @Test
  void verifyOutcomeRejectsNonZeroExitMissingOracleOrDecreasedTestCount() {
    TestCaseId oracle = new TestCaseId("com.demo.PaymentServiceTest", "handlesNullDiscount");
    TestCaseId other = new TestCaseId("com.demo.PaymentServiceTest", "appliesValidDiscount");
    TestSuiteReport baseline = new TestSuiteReport(2, 1, 0, 0, Set.of(other), Set.of(oracle));

    // Non-zero exit code
    TestSuiteReport greenReport = new TestSuiteReport(2, 0, 0, 0, Set.of(other, oracle), Set.of());
    assertThat(policy.verifyOutcome(1, baseline, greenReport).verified()).isFalse();

    // Oracle test missing from passing tests
    TestSuiteReport missingOracle = new TestSuiteReport(2, 0, 0, 0, Set.of(other), Set.of());
    assertThat(policy.verifyOutcome(0, baseline, missingOracle).verified()).isFalse();

    // Executed test count decreased (e.g. a test became skipped)
    TestSuiteReport skippedCount = new TestSuiteReport(2, 0, 0, 1, Set.of(oracle), Set.of());
    assertThat(policy.verifyOutcome(0, baseline, skippedCount).verified()).isFalse();

    // Baseline had no failing tests
    TestSuiteReport noBaselineFailure =
        new TestSuiteReport(2, 0, 0, 0, Set.of(other, oracle), Set.of());
    assertThat(policy.verifyOutcome(0, noBaselineFailure, greenReport).verified()).isFalse();
  }
}
