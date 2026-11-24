package com.autoheal.api;

import com.autoheal.domain.IncidentStatus;
import java.util.UUID;

public record SimulateResponse(UUID runId, IncidentStatus status, String streamUrl) {}
