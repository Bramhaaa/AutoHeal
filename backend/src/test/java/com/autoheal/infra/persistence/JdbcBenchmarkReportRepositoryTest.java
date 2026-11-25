package com.autoheal.infra.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.autoheal.PostgresTestContainer;
import com.autoheal.domain.BenchmarkReport;
import com.autoheal.domain.BenchmarkReportRepository;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
@Import(PostgresTestContainer.class)
class JdbcBenchmarkReportRepositoryTest {

  private final BenchmarkReportRepository repository;
  private final JdbcClient jdbcClient;

  @Autowired
  JdbcBenchmarkReportRepositoryTest(BenchmarkReportRepository repository, JdbcClient jdbcClient) {
    this.repository = repository;
    this.jdbcClient = jdbcClient;
  }

  @BeforeEach
  void cleanTable() {
    jdbcClient.sql("DELETE FROM benchmark_reports").update();
  }

  @Test
  void returnsEmptyWhenNoBenchmarkReportExists() {
    assertThat(repository.findLatest()).isEmpty();
  }

  @Test
  void savesAndReturnsMostRecentBenchmarkReport() {
    Instant earlier = Instant.parse("2026-11-24T10:00:00Z");
    Instant later = Instant.parse("2026-11-25T10:00:00Z");

    BenchmarkReport first =
        repository.save(
            "gemini-2.5-flash",
            "{\"rag\":false,\"maxAttempts\":3}",
            "{\"totalBugs\":25,\"passAt1Rate\":0.52}",
            earlier);
    BenchmarkReport second =
        repository.save(
            "gemini-2.5-flash",
            "{\"rag\":true,\"maxAttempts\":3}",
            "{\"totalBugs\":25,\"passAt1Rate\":0.72}",
            later);

    assertThat(first.id()).isNotNull().isPositive();
    assertThat(second.id()).isGreaterThan(first.id());

    BenchmarkReport latest = repository.findLatest().orElseThrow();
    assertThat(latest.id()).isEqualTo(second.id());
    assertThat(latest.model()).isEqualTo("gemini-2.5-flash");
    assertThat(latest.configJson()).contains("\"rag\"");
    assertThat(latest.resultsJson()).contains("0.72");
    assertThat(latest.createdAt()).isEqualTo(later);
  }
}
