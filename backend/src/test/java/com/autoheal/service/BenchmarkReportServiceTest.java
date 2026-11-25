package com.autoheal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.autoheal.domain.BenchmarkAblationRow;
import com.autoheal.domain.BenchmarkFailureBucket;
import com.autoheal.domain.BenchmarkReport;
import com.autoheal.domain.BenchmarkReportNotFoundException;
import com.autoheal.domain.BenchmarkReportRepository;
import com.autoheal.domain.BenchmarkSummary;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BenchmarkReportServiceTest {

  private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

  @Test
  void throwsNotFoundWhenNoReportExists() {
    BenchmarkReportRepository repository = mock(BenchmarkReportRepository.class);
    when(repository.findLatest()).thenReturn(Optional.empty());
    BenchmarkReportService service = new BenchmarkReportService(repository, objectMapper);

    assertThatThrownBy(service::getLatestSummary)
        .isInstanceOf(BenchmarkReportNotFoundException.class)
        .hasMessageContaining("No benchmark report");
  }

  @Test
  void deserializesLatestReportAndPopulatesIdAndTimestamp() {
    BenchmarkReportRepository repository = mock(BenchmarkReportRepository.class);
    Instant createdAt = Instant.parse("2026-11-25T12:00:00Z");
    String resultsJson =
        """
        {
          "totalBugs": 25,
          "passAt1Rate": 0.68,
          "passAt3Rate": 0.84,
          "medianDurationMs": 28400,
          "p90DurationMs": 49100,
          "meanCostUsd": 0.0042,
          "ablations": [
            {
              "label": "Full pipeline (RAG + 3 attempts)",
              "passAt1Rate": 0.68,
              "passAt3Rate": 0.84,
              "medianDurationMs": 28400,
              "meanCostUsd": 0.0042
            }
          ],
          "failureTaxonomy": [
            {"reason": "MAX_ATTEMPTS", "count": 3},
            {"reason": "TIMEOUT", "count": 1}
          ]
        }
        """;
    when(repository.findLatest())
        .thenReturn(
            Optional.of(
                new BenchmarkReport(
                    7L, "gemini-2.5-flash", "{\"rag\":true}", resultsJson, createdAt)));

    BenchmarkReportService service = new BenchmarkReportService(repository, objectMapper);
    BenchmarkSummary summary = service.getLatestSummary();

    assertThat(summary.reportId()).isEqualTo("7");
    assertThat(summary.generatedAt()).isEqualTo(createdAt);
    assertThat(summary.totalBugs()).isEqualTo(25);
    assertThat(summary.passAt1Rate()).isEqualTo(0.68);
    assertThat(summary.passAt3Rate()).isEqualTo(0.84);
    assertThat(summary.medianDurationMs()).isEqualTo(28400L);
    assertThat(summary.p90DurationMs()).isEqualTo(49100L);
    assertThat(summary.meanCostUsd()).isEqualByComparingTo("0.0042");
    assertThat(summary.ablations()).hasSize(1);
    assertThat(summary.failureTaxonomy()).hasSize(2);
  }

  @Test
  void savesSerializedReportAndReturnsSavedEntity() {
    BenchmarkReportRepository repository = mock(BenchmarkReportRepository.class);
    Instant now = Instant.parse("2026-11-25T12:30:00Z");
    BenchmarkSummary input =
        new BenchmarkSummary(
            null,
            now,
            25,
            0.64,
            0.80,
            25000L,
            45000L,
            new BigDecimal("0.0038"),
            List.of(
                new BenchmarkAblationRow("No RAG", 0.56, 0.72, 27000L, new BigDecimal("0.0035"))),
            List.of(new BenchmarkFailureBucket("MAX_ATTEMPTS", 5)));

    when(repository.save(eq("gemini-2.5-flash"), any(), any(), eq(now)))
        .thenAnswer(
            inv ->
                new BenchmarkReport(
                    11L,
                    inv.getArgument(0),
                    inv.getArgument(1),
                    inv.getArgument(2),
                    inv.getArgument(3)));

    BenchmarkReportService service = new BenchmarkReportService(repository, objectMapper);
    BenchmarkReport saved =
        service.saveReport("gemini-2.5-flash", Map.of("rag", true, "maxAttempts", 3), input, now);

    assertThat(saved.id()).isEqualTo(11L);
    assertThat(saved.configJson()).contains("\"maxAttempts\":3");
    assertThat(saved.resultsJson()).contains("\"totalBugs\":25");
  }
}
