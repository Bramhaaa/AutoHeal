package com.autoheal.infra.docker;

import static org.assertj.core.api.Assertions.assertThat;

import com.autoheal.domain.SandboxRequest;
import com.autoheal.domain.SandboxResult;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DockerSandboxRunnerTest {

  private static final Path DEMO_SERVICE_DIR =
      Path.of("..", "demo-service").toAbsolutePath().normalize();
  private static DockerClient dockerClient;
  private static DockerSandboxRunner runner;

  @BeforeAll
  static void setUpDocker() {
    dockerClient = DockerSandboxClientFactory.createDefault();
    runner = new DockerSandboxRunner(dockerClient);
  }

  @Test
  void runsPassingDemoServiceOfflineAndCleansUpContainer(@TempDir Path workspace)
      throws IOException {
    copyDemoService(workspace);
    List<String> streamedLines = new ArrayList<>();

    SandboxResult result =
        runner.runTests(
            new SandboxRequest(workspace, SandboxRequest.DEFAULT_TIMEOUT), streamedLines::add);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.exitCode()).isZero();
    assertThat(result.timedOut()).isFalse();
    assertThat(result.durationMs()).isPositive();
    assertThat(streamedLines).anyMatch(line -> line.contains("BUILD SUCCESS"));
    assertThat(result.outputLines()).isEqualTo(streamedLines);
    assertThat(remainingAutoHealContainers()).isEmpty();
  }

  @Test
  void returnsNonZeroExitCodeWhenTestsFailAndCleansUpContainer(@TempDir Path workspace)
      throws IOException {
    copyDemoService(workspace);
    Path paymentService =
        workspace.resolve("src/main/java/com/autoheal/demo/payment/PaymentService.java");
    String broken =
        Files.readString(paymentService)
            .replace(
                "if (request.discount() == null || request.discount().percentage() == null) {\n"
                    + "      return subtotal.setScale(2, RoundingMode.HALF_UP);\n"
                    + "    }\n",
                "");
    Files.writeString(paymentService, broken);

    List<String> streamedLines = new ArrayList<>();
    SandboxResult result =
        runner.runTests(
            new SandboxRequest(workspace, SandboxRequest.DEFAULT_TIMEOUT), streamedLines::add);

    assertThat(result.isSuccess()).isFalse();
    assertThat(result.timedOut()).isFalse();
    assertThat(result.exitCode()).isNotZero();
    assertThat(streamedLines).anyMatch(line -> line.contains("BUILD FAILURE"));
    assertThat(remainingAutoHealContainers()).isEmpty();
  }

  @Test
  void killsAndRemovesContainerWhenTimeoutExpires(@TempDir Path workspace) throws IOException {
    copyDemoService(workspace);

    SandboxResult result =
        runner.runTests(new SandboxRequest(workspace, Duration.ofSeconds(1)), line -> {});

    assertThat(result.isSuccess()).isFalse();
    assertThat(result.timedOut()).isTrue();
    assertThat(remainingAutoHealContainers()).isEmpty();
  }

  private List<Container> remainingAutoHealContainers() {
    return dockerClient
        .listContainersCmd()
        .withShowAll(true)
        .withLabelFilter(List.of("autoheal=true"))
        .exec();
  }

  private static void copyDemoService(Path target) throws IOException {
    Files.copy(DEMO_SERVICE_DIR.resolve("pom.xml"), target.resolve("pom.xml"));
    Path srcRoot = DEMO_SERVICE_DIR.resolve("src");
    try (Stream<Path> walk = Files.walk(srcRoot)) {
      for (Path source : walk.toList()) {
        Path dest = target.resolve("src").resolve(srcRoot.relativize(source));
        if (Files.isDirectory(source)) {
          Files.createDirectories(dest);
        } else {
          Files.copy(source, dest);
        }
      }
    }
  }
}
