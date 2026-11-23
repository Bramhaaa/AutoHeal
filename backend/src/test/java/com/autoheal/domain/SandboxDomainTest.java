package com.autoheal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class SandboxDomainTest {

  @Test
  void rejectsNonPositiveTimeout() {
    assertThatThrownBy(() -> new SandboxRequest(Path.of("/tmp"), Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SandboxRequest(Path.of("/tmp"), Duration.ofSeconds(-5)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void reportsSuccessOnlyWhenExitCodeZeroAndNotTimedOut() {
    assertThat(new SandboxResult(0, false, 100L, List.of("ok")).isSuccess()).isTrue();
    assertThat(new SandboxResult(1, false, 100L, List.of("fail")).isSuccess()).isFalse();
    assertThat(new SandboxResult(0, true, 100L, List.of("timeout")).isSuccess()).isFalse();
  }
}
