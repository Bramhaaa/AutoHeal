package com.autoheal.api;

import com.autoheal.domain.IncidentEventType;
import com.autoheal.domain.IncidentStage;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

public record SseEventEnvelope(
    int eventId,
    Instant timestamp,
    IncidentStage stage,
    IncidentEventType type,
    JsonNode payload) {}
