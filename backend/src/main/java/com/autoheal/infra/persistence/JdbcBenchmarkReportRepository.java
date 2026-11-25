package com.autoheal.infra.persistence;

import com.autoheal.domain.BenchmarkReport;
import com.autoheal.domain.BenchmarkReportRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcBenchmarkReportRepository implements BenchmarkReportRepository {

  private final JdbcClient jdbcClient;

  public JdbcBenchmarkReportRepository(JdbcClient jdbcClient) {
    this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
  }

  @Override
  public BenchmarkReport save(
      String model, String configJson, String resultsJson, Instant createdAt) {
    Objects.requireNonNull(model, "model must not be null");
    Objects.requireNonNull(configJson, "configJson must not be null");
    Objects.requireNonNull(resultsJson, "resultsJson must not be null");
    Objects.requireNonNull(createdAt, "createdAt must not be null");

    return jdbcClient
        .sql(
            """
            INSERT INTO benchmark_reports (model, config, results, created_at)
            VALUES (:model, CAST(:config AS jsonb), CAST(:results AS jsonb), :createdAt)
            RETURNING id, model, config::text AS config_json, results::text AS results_json, created_at
            """)
        .param("model", model)
        .param("config", configJson)
        .param("results", resultsJson)
        .param("createdAt", OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC))
        .query(this::mapReport)
        .single();
  }

  @Override
  public Optional<BenchmarkReport> findLatest() {
    return jdbcClient
        .sql(
            """
            SELECT id, model, config::text AS config_json, results::text AS results_json, created_at
            FROM benchmark_reports
            ORDER BY created_at DESC, id DESC
            LIMIT 1
            """)
        .query(this::mapReport)
        .optional();
  }

  private BenchmarkReport mapReport(ResultSet rs, int rowNum) throws SQLException {
    OffsetDateTime created = rs.getObject("created_at", OffsetDateTime.class);
    return new BenchmarkReport(
        rs.getLong("id"),
        rs.getString("model"),
        rs.getString("config_json"),
        rs.getString("results_json"),
        created.toInstant());
  }
}
