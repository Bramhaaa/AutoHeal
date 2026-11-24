package com.autoheal.domain;

public enum IncidentStatus {
  QUEUED,
  PREPARING,
  DISTILLING,
  PATCHING,
  VERIFYING,
  OPENING_PR,
  SUCCEEDED,
  FAILED
}
