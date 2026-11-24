package com.autoheal.service;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "autoheal.abuse")
public record AbuseProtectionProperties(
    int maxLiveRunsPerIpPerHour, BigDecimal dailyBudgetUsd, boolean trustForwardedFor) {

  public AbuseProtectionProperties {
    if (maxLiveRunsPerIpPerHour <= 0) {
      maxLiveRunsPerIpPerHour = 3;
    }
    if (dailyBudgetUsd == null || dailyBudgetUsd.signum() <= 0) {
      dailyBudgetUsd = new BigDecimal("2.00");
    }
  }
}
