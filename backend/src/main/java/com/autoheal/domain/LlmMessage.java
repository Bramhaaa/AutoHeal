package com.autoheal.domain;

import java.util.Objects;

public record LlmMessage(Role role, String content) {

  public enum Role {
    SYSTEM,
    USER,
    ASSISTANT,
    TOOL
  }

  public LlmMessage {
    Objects.requireNonNull(role, "role must not be null");
    Objects.requireNonNull(content, "content must not be null");
  }
}
