package com.autoheal.domain;

public enum AttemptOutcome {
  VERIFIED,
  APPLY_ERROR,
  POLICY_VIOLATION,
  COMPILE_ERROR,
  TEST_FAILURE,
  TIMEOUT,
  TOOL_LIMIT_EXCEEDED
}
