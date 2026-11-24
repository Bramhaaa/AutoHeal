package com.autoheal.domain;

public enum IncidentEventType {
  STAGE_UPDATE,
  LOG_LINE,
  TOOL_CALL,
  PATCH_PROPOSED,
  PATCH_REJECTED,
  ATTEMPT_RESULT,
  COMPLETED,
  FAILED
}
