package com.autoheal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autoheal.domain.PathTraversalException;
import com.autoheal.domain.SearchMatch;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceToolsTest {

  @TempDir Path tempDir;
  private Path workspace;
  private Path outsideDir;
  private WorkspaceTools tools;

  @BeforeEach
  void setUp() throws IOException {
    workspace = Files.createDirectories(tempDir.resolve("workspace"));
    outsideDir = Files.createDirectories(tempDir.resolve("outside"));
    Files.writeString(outsideDir.resolve("secret.txt"), "TOP_SECRET=123\n");

    Files.createDirectories(workspace.resolve("src/main/java/com/demo"));
    Files.createDirectories(workspace.resolve("src/test/java/com/demo"));
    Files.createDirectories(workspace.resolve(".git"));
    Files.createDirectories(workspace.resolve("target"));

    Files.writeString(
        workspace.resolve("src/main/java/com/demo/PaymentService.java"),
        "package com.demo;\n\npublic class PaymentService {\n  public int charge(int x) {\n    return x;\n  }\n}\n");
    Files.writeString(
        workspace.resolve("src/test/java/com/demo/PaymentServiceTest.java"),
        "package com.demo;\n\nclass PaymentServiceTest {\n  void charges() {}\n}\n");
    Files.writeString(workspace.resolve(".git/HEAD"), "ref: refs/heads/main\n");
    Files.writeString(workspace.resolve("target/out.txt"), "charge compiled\n");

    tools = new WorkspaceTools(new WorkspacePathResolver());
  }

  @Test
  void listFilesReturnsSortedRelativePathsSkippingGitAndTarget() {
    List<String> files = tools.listFiles(workspace, "", "");

    assertThat(files)
        .containsExactly(
            "src/main/java/com/demo/PaymentService.java",
            "src/test/java/com/demo/PaymentServiceTest.java");
  }

  @Test
  void listFilesFiltersByDirAndGlob() {
    List<String> files = tools.listFiles(workspace, "src/main", "*.java");

    assertThat(files).containsExactly("src/main/java/com/demo/PaymentService.java");
  }

  @Test
  void listFilesRejectsPathTraversalAndAbsolutePaths() {
    assertThatThrownBy(() -> tools.listFiles(workspace, "../outside", ""))
        .isInstanceOf(PathTraversalException.class);
    assertThatThrownBy(() -> tools.listFiles(workspace, outsideDir.toString(), ""))
        .isInstanceOf(PathTraversalException.class);
  }

  @Test
  void readFileReturnsRequestedLineRangeWithNumbers() {
    String snippet = tools.readFile(workspace, "src/main/java/com/demo/PaymentService.java", 3, 5);

    assertThat(snippet)
        .isEqualTo(
            "3: public class PaymentService {\n4:   public int charge(int x) {\n5:     return x;");
  }

  @Test
  void readFileCapsOutputAt400LinesPerCall() throws IOException {
    StringBuilder big = new StringBuilder();
    for (int i = 1; i <= 500; i++) {
      big.append("line-").append(i).append('\n');
    }
    Files.writeString(workspace.resolve("src/main/java/com/demo/Big.java"), big.toString());

    String result = tools.readFile(workspace, "src/main/java/com/demo/Big.java", 1, 500);
    String[] lines = result.split("\n");

    assertThat(lines).hasSize(400);
    assertThat(lines[0]).isEqualTo("1: line-1");
    assertThat(lines[399]).isEqualTo("400: line-400");
  }

  @Test
  void readFileRejectsSymlinkEscapingWorkspace() throws IOException {
    Path symlink = workspace.resolve("src/main/java/com/demo/leak.txt");
    Files.createSymbolicLink(symlink, outsideDir.resolve("secret.txt"));

    assertThatThrownBy(() -> tools.readFile(workspace, "src/main/java/com/demo/leak.txt", 1, 10))
        .isInstanceOf(PathTraversalException.class);
  }

  @Test
  void readFileRejectsDotDotAndAbsolutePath() {
    assertThatThrownBy(() -> tools.readFile(workspace, "../outside/secret.txt", 1, 10))
        .isInstanceOf(PathTraversalException.class);
    assertThatThrownBy(() -> tools.readFile(workspace, "/etc/passwd", 1, 10))
        .isInstanceOf(PathTraversalException.class);
  }

  @Test
  void searchCodeFindsMatchesExcludingGitTargetAndEscapingSymlinks() throws IOException {
    Path symlink = workspace.resolve("src/main/java/com/demo/leak.txt");
    Files.createSymbolicLink(symlink, outsideDir.resolve("secret.txt"));

    List<SearchMatch> matches = tools.searchCode(workspace, "charge", "*.java");

    assertThat(matches)
        .containsExactly(
            new SearchMatch(
                "src/main/java/com/demo/PaymentService.java", 4, "  public int charge(int x) {"),
            new SearchMatch(
                "src/test/java/com/demo/PaymentServiceTest.java", 4, "  void charges() {}"));

    assertThat(tools.searchCode(workspace, "TOP_SECRET", "")).isEmpty();
  }

  @Test
  void rejectsNulByteBlankPathDanglingSymlinkAndEscapingParentSymlink() throws IOException {
    WorkspacePathResolver resolver = new WorkspacePathResolver();

    assertThatThrownBy(() -> resolver.resolveSafe(workspace, "   "))
        .isInstanceOf(PathTraversalException.class);
    assertThatThrownBy(() -> resolver.resolveSafe(workspace, "src/main/\0evil.java"))
        .isInstanceOf(PathTraversalException.class);

    Path danglingLink = workspace.resolve("src/main/dangling.txt");
    Files.createSymbolicLink(danglingLink, outsideDir.resolve("does-not-exist.txt"));
    assertThatThrownBy(() -> resolver.resolveSafe(workspace, "src/main/dangling.txt"))
        .isInstanceOf(PathTraversalException.class);

    Path dirLink = workspace.resolve("src/main/escapeDir");
    Files.createSymbolicLink(dirLink, outsideDir);
    assertThatThrownBy(() -> resolver.resolveSafe(workspace, "src/main/escapeDir/newfile.txt"))
        .isInstanceOf(PathTraversalException.class);
  }

  @Test
  void validatesReadFileSearchCodeAndListFilesEdgeCases() throws IOException {
    assertThatThrownBy(
            () -> tools.readFile(workspace, "src/main/java/com/demo/PaymentService.java", 0, 5))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> tools.readFile(workspace, "src/main/java/com/demo/PaymentService.java", 5, 2))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> tools.readFile(workspace, "src/main/java/com/demo/Missing.java", 1, 5))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(tools.readFile(workspace, "src/main/java/com/demo/PaymentService.java", 999, 1005))
        .isEmpty();

    assertThatThrownBy(
            () -> tools.listFiles(workspace, "src/main/java/com/demo/PaymentService.java", ""))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> tools.searchCode(workspace, "", ""))
        .isInstanceOf(IllegalArgumentException.class);

    Files.write(
        workspace.resolve("src/main/java/com/demo/binary.dat"),
        new byte[] {'c', 'h', 'a', 'r', 'g', 'e', 0, 1, 2});
    assertThat(tools.searchCode(workspace, "charge", "*.dat")).isEmpty();
  }
}
