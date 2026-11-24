package com.autoheal.service;

import com.autoheal.domain.NewTestFile;
import com.autoheal.domain.PatchProposal;
import com.autoheal.domain.PathTraversalException;
import com.autoheal.domain.PolicyValidationResult;
import com.autoheal.domain.SearchReplaceEdit;
import com.autoheal.domain.TestCaseId;
import com.autoheal.domain.TestSuiteReport;
import com.autoheal.domain.VerificationResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class TestIntegrityPolicy {

  private static final List<Pattern> FORBIDDEN_SKIP_PATTERNS =
      List.of(
          Pattern.compile("@(?:[\\w.]+\\.)?Disabled\\b"),
          Pattern.compile("@(?:[\\w.]+\\.)?Ignore\\b"),
          Pattern.compile("\\bassumeTrue\\s*\\(\\s*false\\b"),
          Pattern.compile("\\bassumeFalse\\s*\\(\\s*true\\b"),
          Pattern.compile("\\bAssumptions\\.abort\\s*\\("));

  private final WorkspacePathResolver pathResolver;

  public TestIntegrityPolicy(WorkspacePathResolver pathResolver) {
    this.pathResolver = Objects.requireNonNull(pathResolver, "pathResolver must not be null");
  }

  public PolicyValidationResult validatePatch(Path workspaceRoot, PatchProposal proposal) {
    Objects.requireNonNull(workspaceRoot, "workspaceRoot must not be null");
    Objects.requireNonNull(proposal, "proposal must not be null");

    List<String> violations = new ArrayList<>();
    if (proposal.edits().isEmpty()) {
      violations.add("Patch must contain at least one edit under src/main/.");
    }

    for (SearchReplaceEdit edit : proposal.edits()) {
      validateEdit(workspaceRoot, edit, violations);
    }

    Set<String> seenNewTestPaths = new HashSet<>();
    for (NewTestFile newTestFile : proposal.newTestFiles()) {
      validateNewTestFile(workspaceRoot, newTestFile, seenNewTestPaths, violations);
    }

    return violations.isEmpty()
        ? PolicyValidationResult.ok()
        : PolicyValidationResult.rejected(violations);
  }

  public VerificationResult verifyOutcome(
      int sandboxExitCode, TestSuiteReport baseline, TestSuiteReport afterPatch) {
    Objects.requireNonNull(baseline, "baseline must not be null");
    Objects.requireNonNull(afterPatch, "afterPatch must not be null");

    List<String> reasons = new ArrayList<>();
    if (sandboxExitCode != 0) {
      reasons.add("Sandbox exited with non-zero code: " + sandboxExitCode);
    }
    if (afterPatch.failures() > 0 || afterPatch.errors() > 0) {
      reasons.add(
          "Post-patch test suite still has failures="
              + afterPatch.failures()
              + ", errors="
              + afterPatch.errors());
    }
    if (baseline.failingTests().isEmpty()) {
      reasons.add("Baseline report has no failing oracle tests to verify.");
    } else {
      for (TestCaseId oracle : baseline.failingTests()) {
        if (!afterPatch.passingTests().contains(oracle)) {
          reasons.add("Originally failing oracle test did not pass: " + oracle);
        }
      }
    }
    if (afterPatch.executedTests() < baseline.executedTests()) {
      reasons.add(
          "Executed test count decreased from "
              + baseline.executedTests()
              + " to "
              + afterPatch.executedTests());
    }

    return reasons.isEmpty() ? VerificationResult.ok() : VerificationResult.rejected(reasons);
  }

  private void validateEdit(Path workspaceRoot, SearchReplaceEdit edit, List<String> violations) {
    Path resolved;
    try {
      resolved = pathResolver.resolveSafe(workspaceRoot, edit.file());
    } catch (PathTraversalException e) {
      violations.add("Edit path rejected: " + e.getMessage());
      return;
    }

    String relPosix = pathResolver.toRelativePosixPath(workspaceRoot, resolved);
    if (!relPosix.startsWith("src/main/")) {
      violations.add("Edits are only permitted under src/main/: " + relPosix);
      return;
    }
    if (!Files.isRegularFile(resolved)) {
      violations.add("Edit target file does not exist: " + relPosix);
    }
  }

  private void validateNewTestFile(
      Path workspaceRoot,
      NewTestFile newTestFile,
      Set<String> seenNewTestPaths,
      List<String> violations) {
    Path resolved;
    try {
      resolved = pathResolver.resolveSafe(workspaceRoot, newTestFile.path());
    } catch (PathTraversalException e) {
      violations.add("New test path rejected: " + e.getMessage());
      return;
    }

    String relPosix = pathResolver.toRelativePosixPath(workspaceRoot, resolved);
    if (!relPosix.startsWith("src/test/")) {
      violations.add("New test files must be placed under src/test/: " + relPosix);
      return;
    }
    if (!seenNewTestPaths.add(relPosix)) {
      violations.add("Duplicate new test file path: " + relPosix);
      return;
    }
    if (Files.exists(resolved)) {
      violations.add("New test file cannot overwrite existing test: " + relPosix);
      return;
    }
    for (Pattern pattern : FORBIDDEN_SKIP_PATTERNS) {
      if (pattern.matcher(newTestFile.content()).find()) {
        violations.add(
            "New test file contains forbidden skip/disable mechanism ("
                + pattern.pattern()
                + "): "
                + relPosix);
        break;
      }
    }
  }
}
