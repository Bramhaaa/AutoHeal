package com.autoheal.domain;

public class RateLimitExceededException extends RuntimeException {

  public RateLimitExceededException(int maxRunsPerHour) {
    super(
        "Rate limit exceeded ("
            + maxRunsPerHour
            + " live runs/hour per IP). Try REPLAY mode instead.");
  }
}
