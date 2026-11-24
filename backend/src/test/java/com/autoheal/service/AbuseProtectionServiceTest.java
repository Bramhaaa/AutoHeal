package com.autoheal.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.autoheal.domain.DailyBudgetExceededException;
import com.autoheal.domain.IncidentRepository;
import com.autoheal.domain.RateLimitExceededException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class AbuseProtectionServiceTest {

  private final Clock fixedClock =
      Clock.fixed(Instant.parse("2026-11-24T12:00:00Z"), ZoneOffset.UTC);

  @Test
  void enforcesPerIpHourlyBucketAndIsolatesDistinctIps() {
    IncidentRepository repo = mock(IncidentRepository.class);
    when(repo.sumDailyLlmCostUsd(any(), any())).thenReturn(new BigDecimal("0.50"));

    AbuseProtectionProperties props =
        new AbuseProtectionProperties(3, new BigDecimal("2.00"), false);
    AbuseProtectionService service = new AbuseProtectionService(repo, props, fixedClock);

    assertThatCode(() -> service.checkLiveRunAllowed("203.0.113.10")).doesNotThrowAnyException();
    assertThatCode(() -> service.checkLiveRunAllowed("203.0.113.10")).doesNotThrowAnyException();
    assertThatCode(() -> service.checkLiveRunAllowed("203.0.113.10")).doesNotThrowAnyException();

    assertThatThrownBy(() -> service.checkLiveRunAllowed("203.0.113.10"))
        .isInstanceOf(RateLimitExceededException.class)
        .hasMessageContaining("REPLAY");

    // Different IP still has its own bucket
    assertThatCode(() -> service.checkLiveRunAllowed("203.0.113.11")).doesNotThrowAnyException();
  }

  @Test
  void tripsDailyBudgetCircuitBreakerBeforeConsumingIpToken() {
    IncidentRepository repo = mock(IncidentRepository.class);
    when(repo.sumDailyLlmCostUsd(
            Instant.parse("2026-11-24T00:00:00Z"), Instant.parse("2026-11-25T00:00:00Z")))
        .thenReturn(new BigDecimal("2.000000"));

    AbuseProtectionProperties props =
        new AbuseProtectionProperties(1, new BigDecimal("2.00"), false);
    AbuseProtectionService service = new AbuseProtectionService(repo, props, fixedClock);

    assertThatThrownBy(() -> service.checkLiveRunAllowed("198.51.100.7"))
        .isInstanceOf(DailyBudgetExceededException.class)
        .hasMessageContaining("REPLAY");

    // Once budget resets / lowers, IP token was not wasted
    when(repo.sumDailyLlmCostUsd(any(), any())).thenReturn(new BigDecimal("0.10"));
    assertThatCode(() -> service.checkLiveRunAllowed("198.51.100.7")).doesNotThrowAnyException();
  }

  @Test
  void appliesSafeDefaultsForNonPositiveProperties() {
    AbuseProtectionProperties defaults = new AbuseProtectionProperties(0, null, true);
    org.assertj.core.api.Assertions.assertThat(defaults.maxLiveRunsPerIpPerHour()).isEqualTo(3);
    org.assertj.core.api.Assertions.assertThat(defaults.dailyBudgetUsd())
        .isEqualByComparingTo("2.00");
    org.assertj.core.api.Assertions.assertThat(defaults.trustForwardedFor()).isTrue();
  }
}
