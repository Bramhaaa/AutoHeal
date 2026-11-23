package com.autoheal.infra.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.HostConfig;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SandboxContainerSpecTest {

  private static final long BYTES_768_MIB = 768L * 1024 * 1024;
  private static final long ONE_CPU_NANO = 1_000_000_000L;

  @Test
  void enforcesAllSecurityInvariants(@TempDir Path workspaceDir) {
    SandboxContainerSpec spec = SandboxContainerSpec.forMavenTest(workspaceDir);
    HostConfig hostConfig = spec.hostConfig();

    assertThat(spec.image()).isEqualTo(SandboxContainerSpec.DEFAULT_IMAGE);
    assertThat(spec.user()).isEqualTo("1000:1000");
    assertThat(spec.workingDir()).isEqualTo("/workspace");
    assertThat(spec.labels()).containsEntry("autoheal", "true");
    assertThat(spec.env())
        .contains("MAVEN_OPTS=-Xmx256m -Djava.io.tmpdir=/tmp")
        .allSatisfy(
            entry -> assertThat(entry).doesNotContain("TOKEN", "SECRET", "KEY", "PASSWORD"));

    assertThat(hostConfig.getNetworkMode()).isEqualTo("none");
    assertThat(hostConfig.getMemory()).isEqualTo(BYTES_768_MIB);
    assertThat(hostConfig.getMemorySwap()).isEqualTo(BYTES_768_MIB);
    assertThat(hostConfig.getNanoCPUs()).isEqualTo(ONE_CPU_NANO);
    assertThat(hostConfig.getPidsLimit()).isEqualTo(256L);
    assertThat(hostConfig.getCapDrop()).containsExactly(Capability.ALL);
    assertThat(hostConfig.getSecurityOpts()).contains("no-new-privileges");
    assertThat(hostConfig.getReadonlyRootfs()).isTrue();
    assertThat(hostConfig.getTmpFs()).containsEntry("/tmp", "rw,noexec,nosuid,size=128m");
    assertThat(hostConfig.getBinds())
        .hasSize(1)
        .allSatisfy(
            bind -> {
              assertThat(bind.getPath()).isEqualTo(workspaceDir.toAbsolutePath().toString());
              assertThat(bind.getVolume().getPath()).isEqualTo("/workspace");
            });
  }

  @Test
  void usesOfflineMavenCommandWithBakedRepo(@TempDir Path workspaceDir) {
    SandboxContainerSpec spec = SandboxContainerSpec.forMavenTest(workspaceDir);

    assertThat(spec.command())
        .containsExactly("mvn", "-o", "-B", "-Dmaven.repo.local=/opt/m2", "test");
  }

  @Test
  void rejectsNonExistentWorkspace(@TempDir Path tempDir) {
    Path missing = tempDir.resolve("does-not-exist");

    assertThatThrownBy(() -> SandboxContainerSpec.forMavenTest(missing))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("directory");
  }
}
