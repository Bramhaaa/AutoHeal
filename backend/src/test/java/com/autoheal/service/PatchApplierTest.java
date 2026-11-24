package com.autoheal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autoheal.domain.AppliedPatch;
import com.autoheal.domain.NewTestFile;
import com.autoheal.domain.PatchApplyException;
import com.autoheal.domain.PatchProposal;
import com.autoheal.domain.PathTraversalException;
import com.autoheal.domain.SearchReplaceEdit;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PatchApplierTest {

  @TempDir Path tempDir;
  private Path workspace;
  private PatchApplier applier;

  @BeforeEach
  void setUp() throws IOException {
    workspace = Files.createDirectories(tempDir.resolve("workspace"));
    Files.createDirectories(workspace.resolve("src/main/java/com/demo"));
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
        workspace.resolve("src/main/java/com/demo/Dup.java"),
        """
        int a = 1;
        int b = 1;
        """);

    applier = new PatchApplier(new WorkspacePathResolver());
  }

  @Test
  void appliesExactEditAndCreatesNewTestFileWithUnifiedDiff() throws IOException {
    PatchProposal proposal =
        new PatchProposal(
            List.of(
                new SearchReplaceEdit(
                    "src/main/java/com/demo/PaymentService.java",
                    "    return 100 - discount;",
                    "    return 100 - (discount == null ? 0 : discount);")),
            List.of(
                new NewTestFile(
                    "src/test/java/com/demo/PaymentRegressionTest.java",
                    "package com.demo;\n\nclass PaymentRegressionTest {}\n")),
            "Null-safe discount handling");

    AppliedPatch applied = applier.apply(workspace, proposal);

    assertThat(applied.modifiedFiles())
        .containsExactly("src/main/java/com/demo/PaymentService.java");
    assertThat(applied.createdFiles())
        .containsExactly("src/test/java/com/demo/PaymentRegressionTest.java");

    String updated =
        Files.readString(workspace.resolve("src/main/java/com/demo/PaymentService.java"));
    assertThat(updated).contains("discount == null ? 0 : discount");
    assertThat(workspace.resolve("src/test/java/com/demo/PaymentRegressionTest.java")).exists();

    assertThat(applied.unifiedDiff())
        .contains("--- a/src/main/java/com/demo/PaymentService.java")
        .contains("+++ b/src/main/java/com/demo/PaymentService.java")
        .contains("-    return 100 - discount;")
        .contains("+    return 100 - (discount == null ? 0 : discount);")
        .contains("+++ b/src/test/java/com/demo/PaymentRegressionTest.java")
        .contains("+class PaymentRegressionTest {}");
  }

  @Test
  void appliesMultipleSequentialEditsToSameFile() throws IOException {
    PatchProposal proposal =
        new PatchProposal(
            List.of(
                new SearchReplaceEdit(
                    "src/main/java/com/demo/PaymentService.java",
                    "public int total(Integer discount)",
                    "public int total(final Integer discount)"),
                new SearchReplaceEdit(
                    "src/main/java/com/demo/PaymentService.java",
                    "return 100 - discount;",
                    "return 100 - (discount != null ? discount : 0);")),
            List.of(),
            "Two edits");

    AppliedPatch applied = applier.apply(workspace, proposal);

    assertThat(applied.modifiedFiles())
        .containsExactly("src/main/java/com/demo/PaymentService.java");
    String updated =
        Files.readString(workspace.resolve("src/main/java/com/demo/PaymentService.java"));
    assertThat(updated)
        .contains("final Integer discount")
        .contains("discount != null ? discount : 0");
  }

  @Test
  void rejectsMissingSearchBlockAndLeavesWorkspaceUntouched() throws IOException {
    String original =
        Files.readString(workspace.resolve("src/main/java/com/demo/PaymentService.java"));
    PatchProposal proposal =
        new PatchProposal(
            List.of(
                new SearchReplaceEdit(
                    "src/main/java/com/demo/PaymentService.java", "doesNotExist()", "x()")),
            List.of(),
            "Bad search");

    assertThatThrownBy(() -> applier.apply(workspace, proposal))
        .isInstanceOf(PatchApplyException.class)
        .hasMessageContaining("not found");

    assertThat(Files.readString(workspace.resolve("src/main/java/com/demo/PaymentService.java")))
        .isEqualTo(original);
  }

  @Test
  void rejectsAmbiguousSearchBlockMatchingMultipleTimes() {
    PatchProposal proposal =
        new PatchProposal(
            List.of(new SearchReplaceEdit("src/main/java/com/demo/Dup.java", "= 1;", "= 2;")),
            List.of(),
            "Ambiguous");

    assertThatThrownBy(() -> applier.apply(workspace, proposal))
        .isInstanceOf(PatchApplyException.class)
        .hasMessageContaining("ambiguous");
  }

  @Test
  void rejectsEmptySearchBlock() {
    PatchProposal proposal =
        new PatchProposal(
            List.of(
                new SearchReplaceEdit(
                    "src/main/java/com/demo/PaymentService.java", "", "anything")),
            List.of(),
            "Empty search");

    assertThatThrownBy(() -> applier.apply(workspace, proposal))
        .isInstanceOf(PatchApplyException.class);
  }

  @Test
  void validatesAllEditsBeforeWritingAnythingToDisk() throws IOException {
    String original =
        Files.readString(workspace.resolve("src/main/java/com/demo/PaymentService.java"));
    PatchProposal proposal =
        new PatchProposal(
            List.of(
                new SearchReplaceEdit(
                    "src/main/java/com/demo/PaymentService.java",
                    "return 100 - discount;",
                    "return 100;"),
                new SearchReplaceEdit(
                    "src/main/java/com/demo/Dup.java", "nonexistent", "replacement")),
            List.of(new NewTestFile("src/test/java/com/demo/NeverWrittenTest.java", "class X {}")),
            "Partial failure");

    assertThatThrownBy(() -> applier.apply(workspace, proposal))
        .isInstanceOf(PatchApplyException.class);

    assertThat(Files.readString(workspace.resolve("src/main/java/com/demo/PaymentService.java")))
        .isEqualTo(original);
    assertThat(workspace.resolve("src/test/java/com/demo/NeverWrittenTest.java")).doesNotExist();
  }

  @Test
  void rejectsPathTraversalInPatch() {
    PatchProposal proposal =
        new PatchProposal(
            List.of(new SearchReplaceEdit("../outside.txt", "a", "b")), List.of(), "Escape");

    assertThatThrownBy(() -> applier.apply(workspace, proposal))
        .isInstanceOf(PathTraversalException.class);
  }
}
