package com.autoheal.domain;

import java.time.Instant;
import java.util.Optional;

public interface BenchmarkReportRepository {

  BenchmarkReport save(String model, String configJson, String resultsJson, Instant createdAt);

  Optional<BenchmarkReport> findLatest();
}
