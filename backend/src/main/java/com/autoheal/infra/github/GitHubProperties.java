package com.autoheal.infra.github;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "autoheal.github")
public record GitHubProperties(String token, String apiBaseUrl) {}
