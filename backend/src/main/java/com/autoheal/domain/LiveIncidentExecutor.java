package com.autoheal.domain;

import java.util.UUID;

@FunctionalInterface
public interface LiveIncidentExecutor {

  void executeLiveRun(UUID runId, ScenarioDefinition scenario);
}
