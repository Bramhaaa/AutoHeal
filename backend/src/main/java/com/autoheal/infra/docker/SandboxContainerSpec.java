package com.autoheal.infra.docker;

import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Volume;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public record SandboxContainerSpec(
    String image,
    String user,
    String workingDir,
    List<String> command,
    List<String> env,
    Map<String, String> labels,
    HostConfig hostConfig) {

  public static final String DEFAULT_IMAGE = "autoheal-maven-runner:java21";
  public static final String SANDBOX_LABEL_KEY = "autoheal";
  public static final String SANDBOX_LABEL_VALUE = "true";

  private static final long MEMORY_LIMIT_BYTES = 768L * 1024 * 1024;
  private static final long ONE_CPU_NANO = 1_000_000_000L;
  private static final long PIDS_LIMIT = 256L;
  private static final String SANDBOX_USER = "1000:1000";
  private static final String WORKSPACE_MOUNT_PATH = "/workspace";

  public static SandboxContainerSpec forMavenTest(Path workspaceDir) {
    return forMavenTest(DEFAULT_IMAGE, workspaceDir);
  }

  public static SandboxContainerSpec forMavenTest(String image, Path workspaceDir) {
    if (workspaceDir == null || !Files.isDirectory(workspaceDir)) {
      throw new IllegalArgumentException(
          "workspaceDir must be an existing directory: " + workspaceDir);
    }

    Path absoluteWorkspace = workspaceDir.toAbsolutePath().normalize();
    HostConfig hostConfig =
        HostConfig.newHostConfig()
            .withNetworkMode("none")
            .withMemory(MEMORY_LIMIT_BYTES)
            .withMemorySwap(MEMORY_LIMIT_BYTES)
            .withNanoCPUs(ONE_CPU_NANO)
            .withPidsLimit(PIDS_LIMIT)
            .withCapDrop(Capability.ALL)
            .withSecurityOpts(List.of("no-new-privileges"))
            .withReadonlyRootfs(true)
            .withTmpFs(Map.of("/tmp", "rw,noexec,nosuid,size=128m"))
            .withBinds(new Bind(absoluteWorkspace.toString(), new Volume(WORKSPACE_MOUNT_PATH)));

    return new SandboxContainerSpec(
        image,
        SANDBOX_USER,
        WORKSPACE_MOUNT_PATH,
        List.of("mvn", "-o", "-B", "-Dmaven.repo.local=/opt/m2", "test"),
        List.of("MAVEN_OPTS=-Xmx256m -Djava.io.tmpdir=/tmp"),
        Map.of(SANDBOX_LABEL_KEY, SANDBOX_LABEL_VALUE),
        hostConfig);
  }
}
