package com.autoheal.domain;

public class ReplayNotAvailableException extends RuntimeException {

  public ReplayNotAvailableException(String scenarioId) {
    super("No recorded successful run available for replay of scenario: " + scenarioId);
  }
}
