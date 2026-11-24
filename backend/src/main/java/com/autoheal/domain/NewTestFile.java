package com.autoheal.domain;

import java.util.Objects;

public record NewTestFile(String path, String content) {

  public NewTestFile {
    Objects.requireNonNull(path, "path must not be null");
    Objects.requireNonNull(content, "content must not be null");
  }
}
