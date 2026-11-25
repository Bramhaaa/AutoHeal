package com.autoheal.service;

import com.autoheal.domain.BenchmarkReport;
import com.autoheal.domain.BenchmarkReportNotFoundException;
import com.autoheal.domain.BenchmarkReportRepository;
import com.autoheal.domain.BenchmarkSummary;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class BenchmarkReportService {

  private final BenchmarkReportRepository repository;
  private final ObjectMapper objectMapper;

  public BenchmarkReportService(BenchmarkReportRepository repository, ObjectMapper objectMapper) {
    this.repository = Objects.requireNonNull(repository, "repository must not be null");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
  }

  public BenchmarkSummary getLatestSummary() {
    BenchmarkReport report =
        repository
            .findLatest()
            .orElseThrow(
                () -> new BenchmarkReportNotFoundException("No benchmark report published yet"));
    BenchmarkSummary parsed = readSummary(report.resultsJson());
    String reportId =
        (parsed.reportId() == null || parsed.reportId().isBlank())
            ? String.valueOf(report.id())
            : parsed.reportId();
    Instant generatedAt = parsed.generatedAt() == null ? report.createdAt() : parsed.generatedAt();
    return new BenchmarkSummary(
        reportId,
        generatedAt,
        parsed.totalBugs(),
        parsed.passAt1Rate(),
        parsed.passAt3Rate(),
        parsed.medianDurationMs(),
        parsed.p90DurationMs(),
        parsed.meanCostUsd(),
        parsed.ablations(),
        parsed.failureTaxonomy());
  }

  public BenchmarkReport saveReport(
      String model, Map<String, Object> config, BenchmarkSummary summary, Instant createdAt) {
    Objects.requireNonNull(model, "model must not be null");
    Objects.requireNonNull(config, "config must not be null");
    Objects.requireNonNull(summary, "summary must not be null");
    Objects.requireNonNull(createdAt, "createdAt must not be null");

    String configJson = writeJson(config, "benchmark config");
    String resultsJson = writeJson(summary, "benchmark summary");
    return repository.save(model, configJson, resultsJson, createdAt);
  }

  private BenchmarkSummary readSummary(String resultsJson) {
    try {
      return objectMapper.readValue(resultsJson, BenchmarkSummary.class);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to deserialize stored benchmark report results", e);
    }
  }

  private String writeJson(Object value, String label) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to serialize " + label + " to JSON", e);
    }
  }
}
