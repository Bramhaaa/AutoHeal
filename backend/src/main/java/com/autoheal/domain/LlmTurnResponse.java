package com.autoheal.domain;

import java.util.Objects;

public record LlmTurnResponse(LlmToolCall toolCall, LlmUsage usage) {

  public LlmTurnResponse {
    Objects.requireNonNull(toolCall, "toolCall must not be null");
    Objects.requireNonNull(usage, "usage must not be null");
  }
}
