package com.autoheal.domain;

import java.util.Objects;

public record TestCaseId(String className, String methodName) {

  public TestCaseId {
    Objects.requireNonNull(className, "className must not be null");
    Objects.requireNonNull(methodName, "methodName must not be null");
  }

  public String qualifiedName() {
    return className + "#" + methodName;
  }
}
