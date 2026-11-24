package com.autoheal.domain;

public enum IncidentStage {
  QUEUED,
  INCIDENT_RECEIVED,
  LOG_DISTILLED,
  AGENT_PATCHING,
  SANDBOX_VERIFY,
  PR_OPENED
}
