package com.autoheal.domain;

import java.util.Objects;

public record ScenarioDefinition(
    String id,
    String title,
    String description,
    String language,
    String branch,
    String oracleTestClass,
    String oracleTestMethod) {

  public ScenarioDefinition {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(title, "title must not be null");
    Objects.requireNonNull(description, "description must not be null");
    Objects.requireNonNull(language, "language must not be null");
    Objects.requireNonNull(branch, "branch must not be null");
    Objects.requireNonNull(oracleTestClass, "oracleTestClass must not be null");
    Objects.requireNonNull(oracleTestMethod, "oracleTestMethod must not be null");
  }
}
