package com.autoheal.api;

import com.autoheal.domain.RunMode;

public record SimulateRequest(String scenarioId, RunMode mode) {}
