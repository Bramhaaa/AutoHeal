package com.autoheal.domain;

import java.util.Objects;

public record SearchReplaceEdit(String file, String search, String replace) {

  public SearchReplaceEdit {
    Objects.requireNonNull(file, "file must not be null");
    Objects.requireNonNull(search, "search must not be null");
    Objects.requireNonNull(replace, "replace must not be null");
  }
}
