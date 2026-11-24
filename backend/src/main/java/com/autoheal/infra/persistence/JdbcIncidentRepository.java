package com.autoheal.infra.persistence;

import com.autoheal.domain.AttemptOutcome;
import com.autoheal.domain.IncidentAttempt;
import com.autoheal.domain.IncidentEvent;
import com.autoheal.domain.IncidentEventType;
import com.autoheal.domain.IncidentRepository;
import com.autoheal.domain.IncidentRun;
import com.autoheal.domain.IncidentSource;
import com.autoheal.domain.IncidentStage;
import com.autoheal.domain.IncidentStatus;
import com.autoheal.domain.LlmCallRecord;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcIncidentRepository implements IncidentRepository {

  private final JdbcClient jdbcClient;

  public JdbcIncidentRepository(JdbcClient jdbcClient) {
    this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
  }

  @Override
  public IncidentRun createRun(IncidentRun run) {
    Objects.requireNonNull(run, "run must not be null");
    jdbcClient
        .sql(
            """
            INSERT INTO incident_runs (
                id, source, scenario_id, repo_name, commit_sha, status, failure_reason,
                attempts_used, rag_enabled, root_cause_summary, generated_diff, pr_url,
                total_prompt_tokens, total_completion_tokens, estimated_cost_usd,
                duration_ms, created_at, completed_at
            ) VALUES (
                :id, :source, :scenarioId, :repoName, :commitSha, :status, :failureReason,
                :attemptsUsed, :ragEnabled, :rootCauseSummary, :generatedDiff, :prUrl,
                :totalPromptTokens, :totalCompletionTokens, :estimatedCostUsd,
                :durationMs, :createdAt, :completedAt
            )
            """)
        .param("id", run.id())
        .param("source", run.source().name())
        .param("scenarioId", run.scenarioId())
        .param("repoName", run.repoName())
        .param("commitSha", run.commitSha())
        .param("status", run.status().name())
        .param("failureReason", run.failureReason())
        .param("attemptsUsed", run.attemptsUsed())
        .param("ragEnabled", run.ragEnabled())
        .param("rootCauseSummary", run.rootCauseSummary())
        .param("generatedDiff", run.generatedDiff())
        .param("prUrl", run.prUrl())
        .param("totalPromptTokens", run.totalPromptTokens())
        .param("totalCompletionTokens", run.totalCompletionTokens())
        .param("estimatedCostUsd", run.estimatedCostUsd())
        .param("durationMs", run.durationMs())
        .param("createdAt", toUtcOffsetDateTime(run.createdAt()))
        .param("completedAt", toUtcOffsetDateTime(run.completedAt()))
        .update();
    return run;
  }

  @Override
  public Optional<IncidentRun> findRunById(UUID runId) {
    Objects.requireNonNull(runId, "runId must not be null");
    return jdbcClient
        .sql(
            """
            SELECT id, source, scenario_id, repo_name, commit_sha, status, failure_reason,
                   attempts_used, rag_enabled, root_cause_summary, generated_diff, pr_url,
                   total_prompt_tokens, total_completion_tokens, estimated_cost_usd,
                   duration_ms, created_at, completed_at
            FROM incident_runs
            WHERE id = :id
            """)
        .param("id", runId)
        .query(this::mapRun)
        .optional();
  }

  @Override
  public Optional<IncidentRun> findLatestSucceededRunByScenario(String scenarioId) {
    Objects.requireNonNull(scenarioId, "scenarioId must not be null");
    return jdbcClient
        .sql(
            """
            SELECT id, source, scenario_id, repo_name, commit_sha, status, failure_reason,
                   attempts_used, rag_enabled, root_cause_summary, generated_diff, pr_url,
                   total_prompt_tokens, total_completion_tokens, estimated_cost_usd,
                   duration_ms, created_at, completed_at
            FROM incident_runs
            WHERE scenario_id = :scenarioId
              AND status = 'SUCCEEDED'
              AND source <> 'REPLAY'
            ORDER BY completed_at DESC NULLS LAST, created_at DESC
            LIMIT 1
            """)
        .param("scenarioId", scenarioId)
        .query(this::mapRun)
        .optional();
  }

  @Override
  public void updateRunStatus(UUID runId, IncidentStatus status) {
    Objects.requireNonNull(runId, "runId must not be null");
    Objects.requireNonNull(status, "status must not be null");
    jdbcClient
        .sql("UPDATE incident_runs SET status = :status WHERE id = :id")
        .param("status", status.name())
        .param("id", runId)
        .update();
  }

  @Override
  public void completeRun(
      UUID runId,
      IncidentStatus status,
      String failureReason,
      int attemptsUsed,
      String rootCauseSummary,
      String generatedDiff,
      String prUrl,
      int totalPromptTokens,
      int totalCompletionTokens,
      BigDecimal estimatedCostUsd,
      long durationMs,
      Instant completedAt) {
    Objects.requireNonNull(runId, "runId must not be null");
    Objects.requireNonNull(status, "status must not be null");
    Objects.requireNonNull(estimatedCostUsd, "estimatedCostUsd must not be null");
    Objects.requireNonNull(completedAt, "completedAt must not be null");

    jdbcClient
        .sql(
            """
            UPDATE incident_runs
            SET status = :status,
                failure_reason = :failureReason,
                attempts_used = :attemptsUsed,
                root_cause_summary = :rootCauseSummary,
                generated_diff = :generatedDiff,
                pr_url = :prUrl,
                total_prompt_tokens = :totalPromptTokens,
                total_completion_tokens = :totalCompletionTokens,
                estimated_cost_usd = :estimatedCostUsd,
                duration_ms = :durationMs,
                completed_at = :completedAt
            WHERE id = :id
            """)
        .param("status", status.name())
        .param("failureReason", failureReason)
        .param("attemptsUsed", attemptsUsed)
        .param("rootCauseSummary", rootCauseSummary)
        .param("generatedDiff", generatedDiff)
        .param("prUrl", prUrl)
        .param("totalPromptTokens", totalPromptTokens)
        .param("totalCompletionTokens", totalCompletionTokens)
        .param("estimatedCostUsd", estimatedCostUsd)
        .param("durationMs", durationMs)
        .param("completedAt", toUtcOffsetDateTime(completedAt))
        .param("id", runId)
        .update();
  }

  @Override
  public void saveAttempt(IncidentAttempt attempt) {
    Objects.requireNonNull(attempt, "attempt must not be null");
    jdbcClient
        .sql(
            """
            INSERT INTO incident_attempts (
                run_id, attempt_no, outcome, diff, sandbox_exit_code,
                tests_run, tests_failed, sandbox_duration_ms, created_at
            ) VALUES (
                :runId, :attemptNo, :outcome, :diff, :sandboxExitCode,
                :testsRun, :testsFailed, :sandboxDurationMs, :createdAt
            )
            """)
        .param("runId", attempt.runId())
        .param("attemptNo", attempt.attemptNo())
        .param("outcome", attempt.outcome().name())
        .param("diff", attempt.diff())
        .param("sandboxExitCode", attempt.sandboxExitCode())
        .param("testsRun", attempt.testsRun())
        .param("testsFailed", attempt.testsFailed())
        .param("sandboxDurationMs", attempt.sandboxDurationMs())
        .param("createdAt", toUtcOffsetDateTime(attempt.createdAt()))
        .update();
  }

  @Override
  public List<IncidentAttempt> findAttemptsByRunId(UUID runId) {
    Objects.requireNonNull(runId, "runId must not be null");
    return jdbcClient
        .sql(
            """
            SELECT run_id, attempt_no, outcome, diff, sandbox_exit_code,
                   tests_run, tests_failed, sandbox_duration_ms, created_at
            FROM incident_attempts
            WHERE run_id = :runId
            ORDER BY attempt_no ASC
            """)
        .param("runId", runId)
        .query(this::mapAttempt)
        .list();
  }

  @Override
  public void saveLlmCall(LlmCallRecord call) {
    Objects.requireNonNull(call, "call must not be null");
    jdbcClient
        .sql(
            """
            INSERT INTO llm_calls (
                run_id, attempt_no, model, prompt_tokens, completion_tokens,
                cost_usd, latency_ms, created_at
            ) VALUES (
                :runId, :attemptNo, :model, :promptTokens, :completionTokens,
                :costUsd, :latencyMs, :createdAt
            )
            """)
        .param("runId", call.runId())
        .param("attemptNo", call.attemptNo())
        .param("model", call.model())
        .param("promptTokens", call.promptTokens())
        .param("completionTokens", call.completionTokens())
        .param("costUsd", call.costUsd())
        .param("latencyMs", call.latencyMs())
        .param("createdAt", toUtcOffsetDateTime(call.createdAt()))
        .update();
  }

  @Override
  public BigDecimal sumDailyLlmCostUsd(Instant dayStartInclusive, Instant dayEndExclusive) {
    Objects.requireNonNull(dayStartInclusive, "dayStartInclusive must not be null");
    Objects.requireNonNull(dayEndExclusive, "dayEndExclusive must not be null");
    return jdbcClient
        .sql(
            """
            SELECT COALESCE(SUM(cost_usd), 0)
            FROM llm_calls
            WHERE created_at >= :start AND created_at < :end
            """)
        .param("start", toUtcOffsetDateTime(dayStartInclusive))
        .param("end", toUtcOffsetDateTime(dayEndExclusive))
        .query(BigDecimal.class)
        .single();
  }

  @Override
  @Transactional
  public IncidentEvent appendEvent(
      UUID runId, IncidentStage stage, IncidentEventType eventType, String payloadJson) {
    Objects.requireNonNull(runId, "runId must not be null");
    Objects.requireNonNull(stage, "stage must not be null");
    Objects.requireNonNull(eventType, "eventType must not be null");
    Objects.requireNonNull(payloadJson, "payloadJson must not be null");

    // Lock parent run row so concurrent writers for the same run cannot collide on event_seq.
    jdbcClient
        .sql("SELECT 1 FROM incident_runs WHERE id = :runId FOR UPDATE")
        .param("runId", runId)
        .query(Integer.class)
        .optional();

    return jdbcClient
        .sql(
            """
            INSERT INTO incident_events (run_id, event_seq, stage, event_type, payload)
            VALUES (
                :runId,
                (SELECT COALESCE(MAX(event_seq), 0) + 1 FROM incident_events WHERE run_id = :runId),
                :stage,
                :eventType,
                CAST(:payload AS jsonb)
            )
            RETURNING run_id, event_seq, stage, event_type, payload::text AS payload_text, created_at
            """)
        .param("runId", runId)
        .param("stage", stage.name())
        .param("eventType", eventType.name())
        .param("payload", payloadJson)
        .query(this::mapEvent)
        .single();
  }

  @Override
  public List<IncidentEvent> findEventsAfter(UUID runId, int afterEventSeq) {
    Objects.requireNonNull(runId, "runId must not be null");
    return jdbcClient
        .sql(
            """
            SELECT run_id, event_seq, stage, event_type, payload::text AS payload_text, created_at
            FROM incident_events
            WHERE run_id = :runId AND event_seq > :afterEventSeq
            ORDER BY event_seq ASC
            """)
        .param("runId", runId)
        .param("afterEventSeq", afterEventSeq)
        .query(this::mapEvent)
        .list();
  }

  private IncidentRun mapRun(ResultSet rs, int rowNum) throws SQLException {
    OffsetDateTime completedAt = rs.getObject("completed_at", OffsetDateTime.class);
    return new IncidentRun(
        rs.getObject("id", UUID.class),
        IncidentSource.valueOf(rs.getString("source")),
        rs.getString("scenario_id"),
        rs.getString("repo_name"),
        rs.getString("commit_sha"),
        IncidentStatus.valueOf(rs.getString("status")),
        rs.getString("failure_reason"),
        rs.getInt("attempts_used"),
        rs.getBoolean("rag_enabled"),
        rs.getString("root_cause_summary"),
        rs.getString("generated_diff"),
        rs.getString("pr_url"),
        rs.getInt("total_prompt_tokens"),
        rs.getInt("total_completion_tokens"),
        rs.getBigDecimal("estimated_cost_usd"),
        rs.getObject("duration_ms", Long.class),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        completedAt == null ? null : completedAt.toInstant());
  }

  private IncidentAttempt mapAttempt(ResultSet rs, int rowNum) throws SQLException {
    return new IncidentAttempt(
        rs.getObject("run_id", UUID.class),
        rs.getInt("attempt_no"),
        AttemptOutcome.valueOf(rs.getString("outcome")),
        rs.getString("diff"),
        rs.getInt("sandbox_exit_code"),
        rs.getInt("tests_run"),
        rs.getInt("tests_failed"),
        rs.getLong("sandbox_duration_ms"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant());
  }

  private IncidentEvent mapEvent(ResultSet rs, int rowNum) throws SQLException {
    return new IncidentEvent(
        rs.getObject("run_id", UUID.class),
        rs.getInt("event_seq"),
        IncidentStage.valueOf(rs.getString("stage")),
        IncidentEventType.valueOf(rs.getString("event_type")),
        rs.getString("payload_text"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant());
  }

  private static OffsetDateTime toUtcOffsetDateTime(Instant instant) {
    return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }
}
