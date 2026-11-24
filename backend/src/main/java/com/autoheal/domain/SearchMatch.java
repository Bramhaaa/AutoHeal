package com.autoheal.domain;

import java.util.Objects;

public record SearchMatch(String path, int lineNumber, String lineContent) {

  public SearchMatch {
    Objects.requireNonNull(path, "path must not be null");
    Objects.requireNonNull(lineContent, "lineContent must not be null");
    if (lineNumber < 1) {
      throw new IllegalArgumentException("lineNumber must be >= 1");
    }
  }
}
