package com.autoheal.domain;

import java.util.UUID;

public class IncidentRunNotFoundException extends RuntimeException {

  public IncidentRunNotFoundException(UUID runId) {
    super("Incident run not found: " + runId);
  }
}
