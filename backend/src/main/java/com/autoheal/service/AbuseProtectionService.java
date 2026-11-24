package com.autoheal.service;

import com.autoheal.domain.DailyBudgetExceededException;
import com.autoheal.domain.IncidentRepository;
import com.autoheal.domain.RateLimitExceededException;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class AbuseProtectionService {

  private final IncidentRepository incidentRepository;
  private final AbuseProtectionProperties properties;
  private final Clock clock;
  private final ConcurrentHashMap<String, Bucket> ipBuckets = new ConcurrentHashMap<>();

  @Autowired
  public AbuseProtectionService(
      IncidentRepository incidentRepository, AbuseProtectionProperties properties) {
    this(incidentRepository, properties, Clock.systemUTC());
  }

  AbuseProtectionService(
      IncidentRepository incidentRepository, AbuseProtectionProperties properties, Clock clock) {
    this.incidentRepository = Objects.requireNonNull(incidentRepository, "incidentRepository");
    this.properties = Objects.requireNonNull(properties, "properties");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public void checkLiveRunAllowed(String clientIp) {
    String normalizedIp = (clientIp == null || clientIp.isBlank()) ? "unknown" : clientIp.trim();

    // Check global daily budget breaker first so IP tokens aren't wasted when breaker is tripped.
    LocalDate todayUtc = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    Instant dayStart = todayUtc.atStartOfDay(ZoneOffset.UTC).toInstant();
    Instant dayEnd = todayUtc.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    BigDecimal spentToday = incidentRepository.sumDailyLlmCostUsd(dayStart, dayEnd);
    if (spentToday.compareTo(properties.dailyBudgetUsd()) >= 0) {
      throw new DailyBudgetExceededException(properties.dailyBudgetUsd());
    }

    Bucket bucket = ipBuckets.computeIfAbsent(normalizedIp, ignored -> newPerIpBucket());
    if (!bucket.tryConsume(1)) {
      throw new RateLimitExceededException(properties.maxLiveRunsPerIpPerHour());
    }
  }

  public boolean trustForwardedFor() {
    return properties.trustForwardedFor();
  }

  private Bucket newPerIpBucket() {
    int limit = properties.maxLiveRunsPerIpPerHour();
    Bandwidth hourlyLimit =
        Bandwidth.builder().capacity(limit).refillGreedy(limit, Duration.ofHours(1)).build();
    return Bucket.builder().addLimit(hourlyLimit).build();
  }
}
