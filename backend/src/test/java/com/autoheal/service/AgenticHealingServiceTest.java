package com.autoheal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.autoheal.domain.AttemptOutcome;
import com.autoheal.domain.DistilledLog;
import com.autoheal.domain.HealingContext;
import com.autoheal.domain.HealingResult;
import com.autoheal.domain.LlmClient;
import com.autoheal.domain.LlmMessage;
import com.autoheal.domain.LlmToolCall;
import com.autoheal.domain.LlmTurnResponse;
import com.autoheal.domain.LlmUsage;
import com.autoheal.domain.PatchProposal;
import com.autoheal.domain.SandboxResult;
import com.autoheal.domain.SandboxRunner;
import com.autoheal.domain.SearchReplaceEdit;
import com.autoheal.domain.TestCaseId;
import com.autoheal.domain.TestSuiteReport;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgenticHealingServiceTest {

  @TempDir Path tempDir;
  private Path workspace;
  private HealingContext context;
  private final WorkspacePathResolver pathResolver = new WorkspacePathResolver();
  private final WorkspaceTools workspaceTools = new WorkspaceTools(pathResolver);
  private final TestIntegrityPolicy policy = new TestIntegrityPolicy(pathResolver);
  private final PatchApplier patchApplier = new PatchApplier(pathResolver);
  private final SurefireReportParser surefireParser = new SurefireReportParser();
  private final LogDistiller logDistiller = new LogDistiller();

  @BeforeEach
  void setUp() throws IOException {
    workspace = Files.createDirectories(tempDir.resolve("workspace"));
    Files.createDirectories(workspace.resolve("src/main/java/com/demo"));
    Files.createDirectories(workspace.resolve("src/test/java/com/demo"));
    Files.writeString(
        workspace.resolve("src/main/java/com/demo/PaymentService.java"),
        """
        package com.demo;

        public class PaymentService {
          public int total(Integer discount) {
            return 100 - discount;
          }
        }
        """);
    Files.writeString(
        workspace.resolve("src/test/java/com/demo/PaymentServiceTest.java"),
        "class PaymentServiceTest {}\n");

    TestCaseId oracle = new TestCaseId("com.demo.PaymentServiceTest", "handlesNullDiscount");
    TestSuiteReport baselineReport = new TestSuiteReport(2, 0, 1, 0, Set.of(), Set.of(oracle));
    DistilledLog baselineLog =
        new DistilledLog(
            "NullPointerException at PaymentService.java:5",
            List.of("com.demo.PaymentServiceTest.handlesNullDiscount"),
            List.of("PaymentService.java"),
            1000,
            45);
    context = new HealingContext(workspace, baselineLog, baselineReport, "recent diff");
  }

  @Test
  void exploresWorkspaceAppliesPatchVerifiesOracleAndAggregatesUsage() {
    ScriptedLlmClient llm =
        new ScriptedLlmClient(
            List.of(
                turn(new LlmToolCall.ListFilesCall("src/main", "*.java"), 100, 20, "0.000100"),
                turn(
                    new LlmToolCall.ReadFileCall(
                        "src/main/java/com/demo/PaymentService.java", 1, 20),
                    150,
                    30,
                    "0.000150"),
                turn(new LlmToolCall.SearchCodeCall("discount", "*.java"), 120, 25, "0.000120"),
                turn(
                    new LlmToolCall.ProposePatchCall(
                        new PatchProposal(
                            List.of(
                                new SearchReplaceEdit(
                                    "src/main/java/com/demo/PaymentService.java",
                                    "return 100 - discount;",
                                    "return 100 - (discount == null ? 0 : discount);")),
                            List.of(),
                            "Guard against null discount")),
                    200,
                    60,
                    "0.000250")));

    SandboxRunner passingSandbox =
        (request, onLine) -> {
          onLine.accept("[INFO] BUILD SUCCESS");
          writeSurefireReport(request.workspaceDir(), 2, 0, 0, true);
          return new SandboxResult(0, false, 1200L, List.of("[INFO] BUILD SUCCESS"));
        };

    AgenticHealingService service = createService(llm, passingSandbox);
    List<String> streamedLines = new ArrayList<>();

    HealingResult result = service.heal(context, streamedLines::add);

    assertThat(result.succeeded()).isTrue();
    assertThat(result.failureReason()).isNull();
    assertThat(result.rootCauseSummary()).isEqualTo("Guard against null discount");
    assertThat(result.unifiedDiff())
        .contains("+    return 100 - (discount == null ? 0 : discount);");
    assertThat(result.modifiedFiles())
        .containsExactly("src/main/java/com/demo/PaymentService.java");
    assertThat(result.createdFiles()).isEmpty();
    assertThat(result.patchedFileContents())
        .containsOnlyKeys("src/main/java/com/demo/PaymentService.java");
    assertThat(result.patchedFileContents().get("src/main/java/com/demo/PaymentService.java"))
        .contains("return 100 - (discount == null ? 0 : discount);");
    assertThat(result.attempts()).hasSize(1);
    assertThat(result.attempts().getFirst().outcome()).isEqualTo(AttemptOutcome.VERIFIED);
    assertThat(result.llmCalls()).hasSize(4);
    assertThat(result.totalPromptTokens()).isEqualTo(570);
    assertThat(result.totalCompletionTokens()).isEqualTo(135);
    assertThat(result.totalCostUsd()).isEqualByComparingTo("0.000620");
    assertThat(streamedLines).contains("[INFO] BUILD SUCCESS");
    // Baseline workspace must remain unmodified
    assertThat(workspace.resolve("src/main/java/com/demo/PaymentService.java"))
        .content()
        .contains("return 100 - discount;");
  }

  @Test
  void selfCorrectsAcrossPolicyViolationAndApplyErrorToVerifyOnAttemptThree() {
    ScriptedLlmClient llm =
        new ScriptedLlmClient(
            List.of(
                // Attempt 1: tries to edit existing test -> POLICY_VIOLATION
                turn(
                    new LlmToolCall.ProposePatchCall(
                        new PatchProposal(
                            List.of(
                                new SearchReplaceEdit(
                                    "src/test/java/com/demo/PaymentServiceTest.java",
                                    "class",
                                    "// class")),
                            List.of(),
                            "Weaken test")),
                    100,
                    20,
                    "0.000100"),
                // Attempt 2: bad search block -> APPLY_ERROR
                turn(
                    new LlmToolCall.ProposePatchCall(
                        new PatchProposal(
                            List.of(
                                new SearchReplaceEdit(
                                    "src/main/java/com/demo/PaymentService.java",
                                    "nonexistentLine();",
                                    "return 0;")),
                            List.of(),
                            "Wrong search")),
                    120,
                    25,
                    "0.000120"),
                // Attempt 3: valid fix -> VERIFIED
                turn(
                    new LlmToolCall.ProposePatchCall(
                        new PatchProposal(
                            List.of(
                                new SearchReplaceEdit(
                                    "src/main/java/com/demo/PaymentService.java",
                                    "return 100 - discount;",
                                    "return 100 - (discount == null ? 0 : discount);")),
                            List.of(),
                            "Null check fixed")),
                    140,
                    30,
                    "0.000140")));

    SandboxRunner passingSandbox =
        (request, onLine) -> {
          writeSurefireReport(request.workspaceDir(), 2, 0, 0, true);
          return new SandboxResult(0, false, 900L, List.of("[INFO] BUILD SUCCESS"));
        };

    HealingResult result = createService(llm, passingSandbox).heal(context, line -> {});

    assertThat(result.succeeded()).isTrue();
    assertThat(result.attempts())
        .extracting(a -> a.outcome())
        .containsExactly(
            AttemptOutcome.POLICY_VIOLATION, AttemptOutcome.APPLY_ERROR, AttemptOutcome.VERIFIED);
    assertThat(llm.seenConversations().get(1)).anyMatch(m -> m.content().contains("src/main/"));
    assertThat(llm.seenConversations().get(2)).anyMatch(m -> m.content().contains("not found"));
  }

  @Test
  void stopsAtMaxAttemptsWhenSandboxFailsOrTimesOut() {
    PatchProposal validProposal =
        new PatchProposal(
            List.of(
                new SearchReplaceEdit(
                    "src/main/java/com/demo/PaymentService.java",
                    "return 100 - discount;",
                    "return 99;")),
            List.of(),
            "Guess");

    ScriptedLlmClient llm =
        new ScriptedLlmClient(
            List.of(
                turn(new LlmToolCall.ProposePatchCall(validProposal), 50, 10, "0.000050"),
                turn(new LlmToolCall.ProposePatchCall(validProposal), 50, 10, "0.000050"),
                turn(new LlmToolCall.ProposePatchCall(validProposal), 50, 10, "0.000050")));

    Queue<SandboxResult> sandboxOutcomes =
        new ArrayDeque<>(
            List.of(
                new SandboxResult(
                    1,
                    false,
                    500L,
                    List.of(
                        "[ERROR] COMPILATION ERROR :", "[ERROR] PaymentService.java:[4,1] err")),
                new SandboxResult(1, false, 600L, List.of("[ERROR] Test failure")),
                new SandboxResult(-1, true, 60_000L, List.of("timed out"))));

    SandboxRunner failingSandbox =
        (request, onLine) -> {
          SandboxResult next = sandboxOutcomes.remove();
          if (!next.timedOut() && next.outputLines().getFirst().contains("Test failure")) {
            writeSurefireReport(request.workspaceDir(), 2, 1, 0, false);
          }
          return next;
        };

    HealingResult result = createService(llm, failingSandbox).heal(context, line -> {});

    assertThat(result.succeeded()).isFalse();
    assertThat(result.failureReason()).isEqualTo("MAX_ATTEMPTS");
    assertThat(result.attempts())
        .extracting(a -> a.outcome())
        .containsExactly(
            AttemptOutcome.COMPILE_ERROR, AttemptOutcome.TEST_FAILURE, AttemptOutcome.TIMEOUT);
  }

  @Test
  void enforcesToolCallCapPerAttempt() {
    List<LlmTurnResponse> turns = new ArrayList<>();
    for (int i = 0; i < AgenticHealingService.MAX_TOOL_CALLS_PER_ATTEMPT * 3; i++) {
      turns.add(turn(new LlmToolCall.ListFilesCall("", ""), 10, 5, "0.000010"));
    }
    ScriptedLlmClient llm = new ScriptedLlmClient(turns);
    SandboxRunner unusedSandbox =
        (request, onLine) -> {
          throw new AssertionError("Sandbox should not run when no patch was proposed");
        };

    HealingResult result = createService(llm, unusedSandbox).heal(context, line -> {});

    assertThat(result.succeeded()).isFalse();
    assertThat(result.failureReason()).isEqualTo("MAX_ATTEMPTS");
    assertThat(result.attempts())
        .hasSize(3)
        .allMatch(a -> a.outcome() == AttemptOutcome.TOOL_LIMIT_EXCEEDED);
  }

  private AgenticHealingService createService(LlmClient llm, SandboxRunner sandboxRunner) {
    return new AgenticHealingService(
        llm, workspaceTools, policy, patchApplier, sandboxRunner, surefireParser, logDistiller);
  }

  private static LlmTurnResponse turn(
      LlmToolCall call, int promptTokens, int completionTokens, String costUsd) {
    return new LlmTurnResponse(
        call,
        new LlmUsage(
            "gemini-2.5-flash", promptTokens, completionTokens, new BigDecimal(costUsd), 120L));
  }

  private static void writeSurefireReport(
      Path attemptWorkspace, int tests, int failures, int errors, boolean oraclePassed) {
    try {
      Path reportDir = Files.createDirectories(attemptWorkspace.resolve("target/surefire-reports"));
      String oracleEntry =
          oraclePassed
              ? "<testcase classname=\"com.demo.PaymentServiceTest\" name=\"handlesNullDiscount\"/>"
              : "<testcase classname=\"com.demo.PaymentServiceTest\" name=\"handlesNullDiscount\"><failure message=\"boom\"/></testcase>";
      String xml =
          """
          <testsuite name="com.demo.PaymentServiceTest" tests="%d" failures="%d" errors="%d" skipped="0">
            <testcase classname="com.demo.PaymentServiceTest" name="otherTest"/>
            %s
          </testsuite>
          """
              .formatted(tests, failures, errors, oracleEntry);
      Files.writeString(reportDir.resolve("TEST-com.demo.PaymentServiceTest.xml"), xml);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private static final class ScriptedLlmClient implements LlmClient {
    private final Queue<LlmTurnResponse> responses;
    private final List<List<LlmMessage>> seenConversations = new ArrayList<>();

    ScriptedLlmClient(List<LlmTurnResponse> responses) {
      this.responses = new ArrayDeque<>(responses);
    }

    @Override
    public LlmTurnResponse nextAction(List<LlmMessage> conversation) {
      seenConversations.add(List.copyOf(conversation));
      if (responses.isEmpty()) {
        throw new IllegalStateException("No more scripted LLM responses");
      }
      return responses.remove();
    }

    List<List<LlmMessage>> seenConversations() {
      return seenConversations;
    }
  }
}
