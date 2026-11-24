package com.autoheal.domain;

public class ScenarioNotFoundException extends RuntimeException {

  public ScenarioNotFoundException(String scenarioId) {
    super("Unknown scenario: " + scenarioId);
  }
}
