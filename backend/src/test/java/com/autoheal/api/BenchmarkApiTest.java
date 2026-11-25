package com.autoheal.api;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.autoheal.PostgresTestContainer;
import com.autoheal.domain.BenchmarkAblationRow;
import com.autoheal.domain.BenchmarkFailureBucket;
import com.autoheal.domain.BenchmarkSummary;
import com.autoheal.domain.LiveIncidentExecutor;
import com.autoheal.service.BenchmarkReportService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestContainer.class)
@MockitoBean(types = LiveIncidentExecutor.class)
class BenchmarkApiTest {

  private final MockMvc mockMvc;
  private final BenchmarkReportService benchmarkReportService;
  private final JdbcClient jdbcClient;

  @Autowired
  BenchmarkApiTest(
      MockMvc mockMvc, BenchmarkReportService benchmarkReportService, JdbcClient jdbcClient) {
    this.mockMvc = mockMvc;
    this.benchmarkReportService = benchmarkReportService;
    this.jdbcClient = jdbcClient;
  }

  @BeforeEach
  void cleanBenchmarks() {
    jdbcClient.sql("DELETE FROM benchmark_reports").update();
  }

  @Test
  void returns404WhenNoBenchmarkReportPublishedYet() throws Exception {
    mockMvc
        .perform(get("/api/v1/benchmarks/summary"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code", is("NOT_FOUND")));
  }

  @Test
  void returnsLatestBenchmarkSummaryMatchingFrontendContract() throws Exception {
    Instant now = Instant.parse("2026-11-25T13:00:00Z");
    BenchmarkSummary summary =
        new BenchmarkSummary(
            null,
            now,
            28,
            0.71,
            0.86,
            24500L,
            46000L,
            new BigDecimal("0.0041"),
            List.of(
                new BenchmarkAblationRow(
                    "Full (RAG + 3 attempts)", 0.71, 0.86, 24500L, new BigDecimal("0.0041")),
                new BenchmarkAblationRow(
                    "No RAG (3 attempts)", 0.61, 0.79, 26100L, new BigDecimal("0.0039"))),
            List.of(
                new BenchmarkFailureBucket("MAX_ATTEMPTS", 3),
                new BenchmarkFailureBucket("POLICY_VIOLATION", 1)));

    benchmarkReportService.saveReport(
        "gemini-2.5-flash", Map.of("rag", true, "maxAttempts", 3), summary, now);

    mockMvc
        .perform(get("/api/v1/benchmarks/summary"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalBugs", is(28)))
        .andExpect(jsonPath("$.passAt1Rate", is(0.71)))
        .andExpect(jsonPath("$.passAt3Rate", is(0.86)))
        .andExpect(jsonPath("$.medianDurationMs", is(24500)))
        .andExpect(jsonPath("$.p90DurationMs", is(46000)))
        .andExpect(jsonPath("$.meanCostUsd", is(0.0041)))
        .andExpect(jsonPath("$.ablations", hasSize(2)))
        .andExpect(jsonPath("$.ablations[0].label", is("Full (RAG + 3 attempts)")))
        .andExpect(jsonPath("$.failureTaxonomy", hasSize(2)))
        .andExpect(jsonPath("$.failureTaxonomy[0].reason", is("MAX_ATTEMPTS")));
  }
}
