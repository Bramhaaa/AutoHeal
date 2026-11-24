package com.autoheal.domain;

import java.util.UUID;

public interface LiveIncidentExecutor {

  void executeLiveRun(UUID runId, ScenarioDefinition scenario);

  void executeWebhookRun(UUID runId, WebhookFailureEvent event);
}
