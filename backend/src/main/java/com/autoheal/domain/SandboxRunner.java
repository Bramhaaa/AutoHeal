package com.autoheal.domain;

import java.time.Duration;
import java.util.function.Consumer;

public interface SandboxRunner {

  SandboxResult runTests(SandboxRequest request, Consumer<String> onOutputLine);

  default int sweepExpiredContainers(Duration maxAge) {
    return 0;
  }
}
