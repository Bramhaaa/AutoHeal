package com.autoheal.domain;

import java.util.function.Consumer;

public interface SandboxRunner {

  SandboxResult runTests(SandboxRequest request, Consumer<String> onOutputLine);
}
