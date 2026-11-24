package com.autoheal.service;

import com.autoheal.domain.AppliedPatch;
import com.autoheal.domain.AttemptOutcome;
import com.autoheal.domain.AttemptRecord;
import com.autoheal.domain.DistilledLog;
import com.autoheal.domain.HealingContext;
import com.autoheal.domain.HealingResult;
import com.autoheal.domain.LlmClient;
import com.autoheal.domain.LlmMessage;
import com.autoheal.domain.LlmMessage.Role;
import com.autoheal.domain.LlmToolCall;
import com.autoheal.domain.LlmTurnResponse;
import com.autoheal.domain.LlmUsage;
import com.autoheal.domain.PatchApplyException;
import com.autoheal.domain.PatchProposal;
import com.autoheal.domain.PathTraversalException;
import com.autoheal.domain.PolicyValidationResult;
import com.autoheal.domain.SandboxRequest;
import com.autoheal.domain.SandboxResult;
import com.autoheal.domain.SandboxRunner;
import com.autoheal.domain.SearchMatch;
import com.autoheal.domain.TestSuiteReport;
import com.autoheal.domain.VerificationResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class AgenticHealingService {

  public static final int MAX_ATTEMPTS = 3;
  public static final int MAX_TOOL_CALLS_PER_ATTEMPT = 15;
  private static final Set<String> SKIPPED_COPY_DIRS = Set.of(".git", "target");

  private final LlmClient llmClient;
  private final WorkspaceTools workspaceTools;
  private final TestIntegrityPolicy integrityPolicy;
  private final PatchApplier patchApplier;
  private final SandboxRunner sandboxRunner;
  private final SurefireReportParser surefireParser;
  private final LogDistiller logDistiller;

  public AgenticHealingService(
      LlmClient llmClient,
      WorkspaceTools workspaceTools,
      TestIntegrityPolicy integrityPolicy,
      PatchApplier patchApplier,
      SandboxRunner sandboxRunner,
      SurefireReportParser surefireParser,
      LogDistiller logDistiller) {
    this.llmClient = Objects.requireNonNull(llmClient, "llmClient must not be null");
    this.workspaceTools = Objects.requireNonNull(workspaceTools, "workspaceTools must not be null");
    this.integrityPolicy =
        Objects.requireNonNull(integrityPolicy, "integrityPolicy must not be null");
    this.patchApplier = Objects.requireNonNull(patchApplier, "patchApplier must not be null");
    this.sandboxRunner = Objects.requireNonNull(sandboxRunner, "sandboxRunner must not be null");
    this.surefireParser = Objects.requireNonNull(surefireParser, "surefireParser must not be null");
    this.logDistiller = Objects.requireNonNull(logDistiller, "logDistiller must not be null");
  }

  public HealingResult heal(HealingContext context, Consumer<String> onSandboxLine) {
    Objects.requireNonNull(context, "context must not be null");
    Objects.requireNonNull(onSandboxLine, "onSandboxLine must not be null");

    List<LlmMessage> conversation = new ArrayList<>();
    conversation.add(new LlmMessage(Role.SYSTEM, systemPrompt()));
    conversation.add(new LlmMessage(Role.USER, initialUserPrompt(context)));

    List<AttemptRecord> attempts = new ArrayList<>();
    List<LlmUsage> llmCalls = new ArrayList<>();

    for (int attemptNo = 1; attemptNo <= MAX_ATTEMPTS; attemptNo++) {
      PatchProposal proposal = runToolLoop(context.workspaceRoot(), conversation, llmCalls);
      if (proposal == null) {
        String feedback =
            "Reached maximum of "
                + MAX_TOOL_CALLS_PER_ATTEMPT
                + " tool calls without calling propose_patch.";
        attempts.add(
            new AttemptRecord(
                attemptNo, AttemptOutcome.TOOL_LIMIT_EXCEEDED, "", -1, 0, 0, 0L, feedback));
        conversation.add(new LlmMessage(Role.USER, feedback + " Submit a patch now."));
        continue;
      }

      AttemptRecord record = evaluateProposal(attemptNo, context, proposal, onSandboxLine);
      attempts.add(record);
      if (record.outcome() == AttemptOutcome.VERIFIED) {
        return HealingResult.succeeded(
            proposal.rootCauseSummary(), record.unifiedDiff(), attempts, llmCalls);
      }
      conversation.add(
          new LlmMessage(
              Role.USER,
              "Attempt "
                  + attemptNo
                  + " failed ("
                  + record.outcome()
                  + "):\n"
                  + record.feedback()
                  + "\nPlease analyze the error and propose a corrected patch."));
    }

    return HealingResult.failed("MAX_ATTEMPTS", attempts, llmCalls);
  }

  private PatchProposal runToolLoop(
      Path workspaceRoot, List<LlmMessage> conversation, List<LlmUsage> llmCalls) {
    for (int step = 0; step < MAX_TOOL_CALLS_PER_ATTEMPT; step++) {
      LlmTurnResponse turn = llmClient.nextAction(List.copyOf(conversation));
      llmCalls.add(turn.usage());

      LlmToolCall toolCall = turn.toolCall();
      if (toolCall instanceof LlmToolCall.ProposePatchCall propose) {
        return propose.proposal();
      }
      String toolOutput = executeReadTool(workspaceRoot, toolCall);
      conversation.add(new LlmMessage(Role.TOOL, toolOutput));
    }
    return null;
  }

  private String executeReadTool(Path workspaceRoot, LlmToolCall toolCall) {
    try {
      return switch (toolCall) {
        case LlmToolCall.ListFilesCall c ->
            String.join("\n", workspaceTools.listFiles(workspaceRoot, c.dir(), c.glob()));
        case LlmToolCall.ReadFileCall c ->
            workspaceTools.readFile(workspaceRoot, c.path(), c.startLine(), c.endLine());
        case LlmToolCall.SearchCodeCall c ->
            workspaceTools.searchCode(workspaceRoot, c.query(), c.glob()).stream()
                .map(SearchMatch::toString)
                .collect(Collectors.joining("\n"));
        case LlmToolCall.ProposePatchCall ignored ->
            throw new IllegalStateException("ProposePatchCall must be handled by caller");
      };
    } catch (PathTraversalException | IllegalArgumentException e) {
      return "TOOL_ERROR: " + e.getMessage();
    }
  }

  private AttemptRecord evaluateProposal(
      int attemptNo,
      HealingContext context,
      PatchProposal proposal,
      Consumer<String> onSandboxLine) {
    Path attemptWorkspace = copyCleanWorkspace(context.workspaceRoot());
    try {
      PolicyValidationResult policyResult =
          integrityPolicy.validatePatch(attemptWorkspace, proposal);
      if (!policyResult.allowed()) {
        return new AttemptRecord(
            attemptNo,
            AttemptOutcome.POLICY_VIOLATION,
            "",
            -1,
            0,
            0,
            0L,
            String.join("; ", policyResult.violations()));
      }

      AppliedPatch applied;
      try {
        applied = patchApplier.apply(attemptWorkspace, proposal);
      } catch (PatchApplyException | PathTraversalException e) {
        return new AttemptRecord(
            attemptNo, AttemptOutcome.APPLY_ERROR, "", -1, 0, 0, 0L, e.getMessage());
      }

      SandboxResult sandbox =
          sandboxRunner.runTests(
              new SandboxRequest(attemptWorkspace, SandboxRequest.DEFAULT_TIMEOUT), onSandboxLine);
      if (sandbox.timedOut()) {
        return new AttemptRecord(
            attemptNo,
            AttemptOutcome.TIMEOUT,
            applied.unifiedDiff(),
            sandbox.exitCode(),
            0,
            0,
            sandbox.durationMs(),
            "Sandbox timed out after " + sandbox.durationMs() + "ms");
      }

      TestSuiteReport afterPatch = surefireParser.parse(attemptWorkspace);
      VerificationResult verification =
          integrityPolicy.verifyOutcome(sandbox.exitCode(), context.baselineReport(), afterPatch);
      if (verification.verified()) {
        return new AttemptRecord(
            attemptNo,
            AttemptOutcome.VERIFIED,
            applied.unifiedDiff(),
            sandbox.exitCode(),
            afterPatch.executedTests(),
            afterPatch.failures() + afterPatch.errors(),
            sandbox.durationMs(),
            "Verified");
      }

      String rawOutput = String.join("\n", sandbox.outputLines());
      DistilledLog distilled = logDistiller.distill(rawOutput);
      AttemptOutcome outcome =
          rawOutput.contains("COMPILATION ERROR")
              ? AttemptOutcome.COMPILE_ERROR
              : AttemptOutcome.TEST_FAILURE;
      String feedback =
          String.join("; ", verification.reasons())
              + (distilled.summary().isEmpty() ? "" : "\n" + distilled.summary());
      return new AttemptRecord(
          attemptNo,
          outcome,
          applied.unifiedDiff(),
          sandbox.exitCode(),
          afterPatch.executedTests(),
          afterPatch.failures() + afterPatch.errors(),
          sandbox.durationMs(),
          feedback);
    } finally {
      deleteRecursively(attemptWorkspace);
    }
  }

  private Path copyCleanWorkspace(Path sourceRoot) {
    try {
      Path targetRoot = Files.createTempDirectory("autoheal-attempt-");
      Files.walkFileTree(
          sourceRoot,
          new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                throws IOException {
              if (!dir.equals(sourceRoot)) {
                Path name = dir.getFileName();
                if (name != null && SKIPPED_COPY_DIRS.contains(name.toString())) {
                  return FileVisitResult.SKIP_SUBTREE;
                }
                Files.createDirectories(targetRoot.resolve(sourceRoot.relativize(dir)));
              }
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                throws IOException {
              if (attrs.isRegularFile()) {
                Path dest = targetRoot.resolve(sourceRoot.relativize(file));
                Files.copy(file, dest, StandardCopyOption.REPLACE_EXISTING);
              }
              return FileVisitResult.CONTINUE;
            }
          });
      return targetRoot;
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to copy workspace for patch attempt", e);
    }
  }

  private void deleteRecursively(Path dir) {
    if (!Files.exists(dir)) {
      return;
    }
    try (Stream<Path> walk = Files.walk(dir)) {
      walk.sorted(Comparator.reverseOrder())
          .forEach(
              path -> {
                try {
                  Files.deleteIfExists(path);
                } catch (IOException e) {
                  throw new UncheckedIOException("Failed to delete " + path, e);
                }
              });
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to clean up attempt workspace: " + dir, e);
    }
  }

  private String systemPrompt() {
    return """
        You are AutoHeal, a Java/Spring Boot incident triage agent.
        Use list_files, read_file, and search_code to inspect the workspace, then call propose_patch.
        Rules: only edit existing files under src/main/; never modify existing tests or build files.
        """;
  }

  private String initialUserPrompt(HealingContext context) {
    return "Failing tests: "
        + context.baselineReport().failingTests()
        + "\n\nDistilled failure log:\n"
        + context.baselineLog().summary()
        + (context.recentGitDiffs().isBlank()
            ? ""
            : "\n\nRecent Git diffs:\n" + context.recentGitDiffs());
  }
}
