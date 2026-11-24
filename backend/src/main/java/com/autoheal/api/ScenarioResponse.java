package com.autoheal.api;

public record ScenarioResponse(
    String id, String title, String description, String language, boolean replayAvailable) {}
