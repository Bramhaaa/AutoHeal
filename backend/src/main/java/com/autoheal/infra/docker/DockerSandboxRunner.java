package com.autoheal.infra.docker;

import com.autoheal.domain.SandboxRequest;
import com.autoheal.domain.SandboxResult;
import com.autoheal.domain.SandboxRunner;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.WaitContainerResultCallback;
import com.github.dockerjava.api.exception.DockerClientException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Frame;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DockerSandboxRunner implements SandboxRunner {

  private static final Logger log = LoggerFactory.getLogger(DockerSandboxRunner.class);

  private final DockerClient dockerClient;
  private final String runnerImage;

  public DockerSandboxRunner(DockerClient dockerClient) {
    this(dockerClient, SandboxContainerSpec.DEFAULT_IMAGE);
  }

  public DockerSandboxRunner(DockerClient dockerClient, String runnerImage) {
    this.dockerClient = Objects.requireNonNull(dockerClient, "dockerClient must not be null");
    this.runnerImage = Objects.requireNonNull(runnerImage, "runnerImage must not be null");
  }

  @Override
  public SandboxResult runTests(SandboxRequest request, Consumer<String> onOutputLine) {
    Objects.requireNonNull(request, "request must not be null");
    Objects.requireNonNull(onOutputLine, "onOutputLine must not be null");

    SandboxContainerSpec spec =
        SandboxContainerSpec.forMavenTest(runnerImage, request.workspaceDir());
    List<String> outputLines = Collections.synchronizedList(new ArrayList<>());
    long startNanos = System.nanoTime();

    CreateContainerResponse created =
        dockerClient
            .createContainerCmd(spec.image())
            .withUser(spec.user())
            .withWorkingDir(spec.workingDir())
            .withCmd(spec.command())
            .withEnv(spec.env())
            .withLabels(spec.labels())
            .withHostConfig(spec.hostConfig())
            .exec();
    String containerId = created.getId();

    boolean timedOut = false;
    int exitCode = -1;
    LineBufferingCallback logCallback = new LineBufferingCallback(outputLines, onOutputLine);

    try {
      dockerClient.startContainerCmd(containerId).exec();
      dockerClient
          .logContainerCmd(containerId)
          .withStdOut(true)
          .withStdErr(true)
          .withFollowStream(true)
          .exec(logCallback);

      WaitContainerResultCallback waitCallback = new WaitContainerResultCallback();
      dockerClient.waitContainerCmd(containerId).exec(waitCallback);
      try {
        Integer status =
            waitCallback.awaitStatusCode(request.timeout().toMillis(), TimeUnit.MILLISECONDS);
        exitCode = status == null ? -1 : status;
        logCallback.awaitCompletion(2, TimeUnit.SECONDS);
      } catch (DockerClientException timeoutEx) {
        timedOut = true;
        log.warn("Sandbox container {} timed out after {}", containerId, request.timeout());
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        timedOut = true;
      }
    } finally {
      closeQuietly(logCallback);
      forceRemoveContainer(containerId);
    }

    logCallback.flushRemaining();
    long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    return new SandboxResult(exitCode, timedOut, durationMs, outputLines);
  }

  @Override
  public int sweepExpiredContainers(Duration maxAge) {
    long cutoffEpochSeconds = Instant.now().minus(maxAge).getEpochSecond();
    List<Container> containers =
        dockerClient
            .listContainersCmd()
            .withShowAll(true)
            .withLabelFilter(
                List.of(
                    SandboxContainerSpec.SANDBOX_LABEL_KEY
                        + "="
                        + SandboxContainerSpec.SANDBOX_LABEL_VALUE))
            .exec();

    int removed = 0;
    for (Container container : containers) {
      if (container.getCreated() != null && container.getCreated() <= cutoffEpochSeconds) {
        forceRemoveContainer(container.getId());
        removed++;
      }
    }
    return removed;
  }

  private void forceRemoveContainer(String containerId) {
    try {
      dockerClient.removeContainerCmd(containerId).withForce(true).withRemoveVolumes(true).exec();
    } catch (NotFoundException ignored) {
      // Already removed.
    }
  }

  private void closeQuietly(LineBufferingCallback callback) {
    try {
      callback.close();
    } catch (IOException e) {
      log.debug("Ignoring error while closing Docker log stream", e);
    }
  }

  private static final class LineBufferingCallback extends ResultCallback.Adapter<Frame> {

    private final List<String> outputLines;
    private final Consumer<String> onOutputLine;
    private final StringBuilder partial = new StringBuilder();

    private LineBufferingCallback(List<String> outputLines, Consumer<String> onOutputLine) {
      this.outputLines = outputLines;
      this.onOutputLine = onOutputLine;
    }

    @Override
    public synchronized void onNext(Frame frame) {
      if (frame == null || frame.getPayload() == null) {
        return;
      }
      String chunk = new String(frame.getPayload(), StandardCharsets.UTF_8);
      partial.append(chunk);
      int newlineIdx;
      while ((newlineIdx = partial.indexOf("\n")) >= 0) {
        String line = partial.substring(0, newlineIdx);
        if (line.endsWith("\r")) {
          line = line.substring(0, line.length() - 1);
        }
        partial.delete(0, newlineIdx + 1);
        emit(line);
      }
    }

    synchronized void flushRemaining() {
      if (!partial.isEmpty()) {
        emit(partial.toString());
        partial.setLength(0);
      }
    }

    private void emit(String line) {
      outputLines.add(line);
      onOutputLine.accept(line);
    }
  }
}
