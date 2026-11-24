package com.autoheal.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "autoheal.webhook")
public record WebhookProperties(String secret, int maxDeliveryCacheSize) {

  public WebhookProperties {
    if (secret == null) {
      secret = "";
    }
    if (maxDeliveryCacheSize <= 0) {
      maxDeliveryCacheSize = 1_000;
    }
  }
}
