package com.autoheal.infra.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autoheal.PostgresTestContainer;
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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
@Import(PostgresTestContainer.class)
class JdbcIncidentRepositoryTest {

  private final IncidentRepository repository;
  private final JdbcClient jdbcClient;

  @Autowired
  JdbcIncidentRepositoryTest(IncidentRepository repository, JdbcClient jdbcClient) {
    this.repository = repository;
    this.jdbcClient = jdbcClient;
  }

  @Test
  void flywayCreatesAllPrdTablesPgvectorExtensionAndHnswIndex() {
    List<String> tables =
        jdbcClient
            .sql(
                """
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'public'
                """)
            .query(String.class)
            .list();

    assertThat(tables)
        .contains(
            "incident_runs",
            "incident_attempts",
            "llm_calls",
            "incident_events",
            "incident_memory",
            "benchmark_reports");

    Boolean vectorInstalled =
        jdbcClient
            .sql("SELECT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'vector')")
            .query(Boolean.class)
            .single();
    assertThat(vectorInstalled).isTrue();

    Boolean hnswIndexExists =
        jdbcClient
            .sql(
                """
                SELECT EXISTS (
                    SELECT 1 FROM pg_indexes
                    WHERE indexname = 'idx_incident_memory_embedding'
                )
                """)
            .query(Boolean.class)
            .single();
    assertThat(hnswIndexExists).isTrue();
  }

  @Test
  void createsUpdatesAndCompletesIncidentRun() {
    UUID runId = UUID.randomUUID();
    Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    IncidentRun queued =
        IncidentRun.queued(
            runId,
            IncidentSource.PLAYGROUND,
            "payment-npe-01",
            "owner/demo-service",
            "abc123def456",
            true,
            createdAt);

    repository.createRun(queued);

    Optional<IncidentRun> loaded = repository.findRunById(runId);
    assertThat(loaded).isPresent();
    assertThat(loaded.get().status()).isEqualTo(IncidentStatus.QUEUED);
    assertThat(loaded.get().scenarioId()).isEqualTo("payment-npe-01");
    assertThat(loaded.get().ragEnabled()).isTrue();

    repository.updateRunStatus(runId, IncidentStatus.PATCHING);
    assertThat(repository.findRunById(runId).orElseThrow().status())
        .isEqualTo(IncidentStatus.PATCHING);

    Instant completedAt = createdAt.plusSeconds(42);
    repository.completeRun(
        runId,
        IncidentStatus.SUCCEEDED,
        null,
        2,
        "Null check added for missing payment customer",
        "--- a/PaymentService.java\n+++ b/PaymentService.java",
        "https://github.com/owner/demo-service/pull/17",
        1800,
        420,
        new BigDecimal("0.000522"),
        42_000L,
        completedAt);

    IncidentRun completed = repository.findRunById(runId).orElseThrow();
    assertThat(completed.status()).isEqualTo(IncidentStatus.SUCCEEDED);
    assertThat(completed.attemptsUsed()).isEqualTo(2);
    assertThat(completed.rootCauseSummary())
        .isEqualTo("Null check added for missing payment customer");
    assertThat(completed.prUrl()).isEqualTo("https://github.com/owner/demo-service/pull/17");
    assertThat(completed.totalPromptTokens()).isEqualTo(1800);
    assertThat(completed.totalCompletionTokens()).isEqualTo(420);
    assertThat(completed.estimatedCostUsd()).isEqualByComparingTo("0.000522");
    assertThat(completed.durationMs()).isEqualTo(42_000L);
    assertThat(completed.completedAt()).isEqualTo(completedAt);
  }

  @Test
  void savesAndListsAttemptsInOrderAndEnforcesUniqueAttemptNo() {
    UUID runId = createQueuedRun();
    Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

    repository.saveAttempt(
        new IncidentAttempt(
            runId, 1, AttemptOutcome.TEST_FAILURE, "diff-1", 1, 12, 1, 8_500L, now));
    repository.saveAttempt(
        new IncidentAttempt(
            runId, 2, AttemptOutcome.VERIFIED, "diff-2", 0, 12, 0, 8_100L, now.plusSeconds(10)));

    List<IncidentAttempt> attempts = repository.findAttemptsByRunId(runId);
    assertThat(attempts).hasSize(2);
    assertThat(attempts.get(0).attemptNo()).isEqualTo(1);
    assertThat(attempts.get(0).outcome()).isEqualTo(AttemptOutcome.TEST_FAILURE);
    assertThat(attempts.get(1).attemptNo()).isEqualTo(2);
    assertThat(attempts.get(1).outcome()).isEqualTo(AttemptOutcome.VERIFIED);

    assertThatThrownBy(
            () ->
                repository.saveAttempt(
                    new IncidentAttempt(
                        runId, 1, AttemptOutcome.COMPILE_ERROR, "dup", 1, 0, 0, 500L, now)))
        .isInstanceOf(DuplicateKeyException.class);
  }

  @Test
  void recordsLlmCallsAndSumsDailyCostWithinWindow() {
    UUID runId = createQueuedRun();
    Instant dayStart = Instant.parse("2026-11-24T00:00:00Z");
    Instant dayEnd = Instant.parse("2026-11-25T00:00:00Z");

    repository.saveLlmCall(
        new LlmCallRecord(
            runId,
            1,
            "gemini-2.5-flash",
            1000,
            200,
            new BigDecimal("0.000270"),
            450L,
            Instant.parse("2026-11-24T10:00:00Z")));
    repository.saveLlmCall(
        new LlmCallRecord(
            runId,
            2,
            "gemini-2.5-flash",
            2000,
            500,
            new BigDecimal("0.000600"),
            510L,
            Instant.parse("2026-11-24T11:30:00Z")));
    // Previous day — must NOT be included in today's sum
    repository.saveLlmCall(
        new LlmCallRecord(
            runId,
            1,
            "gemini-2.5-flash",
            9999,
            9999,
            new BigDecimal("1.500000"),
            700L,
            Instant.parse("2026-11-23T23:59:59Z")));

    BigDecimal todayCost = repository.sumDailyLlmCostUsd(dayStart, dayEnd);
    assertThat(todayCost).isEqualByComparingTo("0.000870");

    BigDecimal emptyDayCost =
        repository.sumDailyLlmCostUsd(
            Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-02T00:00:00Z"));
    assertThat(emptyDayCost).isEqualByComparingTo(BigDecimal.ZERO);
  }

  @Test
  void appendsMonotonicEventsAndReplaysStrictlyAfterLastEventId() {
    UUID runId = createQueuedRun();

    IncidentEvent e1 =
        repository.appendEvent(
            runId,
            IncidentStage.QUEUED,
            IncidentEventType.STAGE_UPDATE,
            "{\"message\":\"Run queued\"}");
    IncidentEvent e2 =
        repository.appendEvent(
            runId,
            IncidentStage.LOG_DISTILLED,
            IncidentEventType.LOG_LINE,
            "{\"message\":\"Distilled 82% noise\"}");
    IncidentEvent e3 =
        repository.appendEvent(
            runId,
            IncidentStage.SANDBOX_VERIFY,
            IncidentEventType.ATTEMPT_RESULT,
            "{\"attempt\":1,\"outcome\":\"VERIFIED\"}");

    assertThat(e1.eventSeq()).isEqualTo(1);
    assertThat(e2.eventSeq()).isEqualTo(2);
    assertThat(e3.eventSeq()).isEqualTo(3);

    List<IncidentEvent> all = repository.findEventsAfter(runId, 0);
    assertThat(all).extracting(IncidentEvent::eventSeq).containsExactly(1, 2, 3);

    List<IncidentEvent> replayed = repository.findEventsAfter(runId, 1);
    assertThat(replayed).extracting(IncidentEvent::eventSeq).containsExactly(2, 3);
    assertThat(replayed.get(0).stage()).isEqualTo(IncidentStage.LOG_DISTILLED);
    assertThat(replayed.get(1).eventType()).isEqualTo(IncidentEventType.ATTEMPT_RESULT);
    assertThat(replayed.get(1).payloadJson()).contains("\"VERIFIED\"");
  }

  @Test
  void findsLatestNonReplaySucceededRunForScenario() {
    String scenario = "jpql-typo-04";
    Instant base = Instant.now().truncatedTo(ChronoUnit.MICROS);

    UUID failedId = UUID.randomUUID();
    repository.createRun(
        IncidentRun.queued(
            failedId, IncidentSource.PLAYGROUND, scenario, "owner/demo", "sha1", true, base));
    repository.completeRun(
        failedId,
        IncidentStatus.FAILED,
        "MAX_ATTEMPTS",
        3,
        null,
        null,
        null,
        100,
        50,
        BigDecimal.ZERO,
        1000L,
        base.plusSeconds(10));

    UUID succeededId = UUID.randomUUID();
    repository.createRun(
        IncidentRun.queued(
            succeededId,
            IncidentSource.PLAYGROUND,
            scenario,
            "owner/demo",
            "sha2",
            true,
            base.plusSeconds(20)));
    repository.completeRun(
        succeededId,
        IncidentStatus.SUCCEEDED,
        null,
        1,
        "Fixed JPQL",
        "diff",
        null,
        100,
        50,
        BigDecimal.ZERO,
        1000L,
        base.plusSeconds(30));

    UUID replayId = UUID.randomUUID();
    repository.createRun(
        IncidentRun.queued(
            replayId,
            IncidentSource.REPLAY,
            scenario,
            "owner/demo",
            "sha2",
            false,
            base.plusSeconds(40)));
    repository.completeRun(
        replayId,
        IncidentStatus.SUCCEEDED,
        null,
        1,
        "Fixed JPQL",
        "diff",
        null,
        0,
        0,
        BigDecimal.ZERO,
        500L,
        base.plusSeconds(41));

    Optional<IncidentRun> latest = repository.findLatestSucceededRunByScenario(scenario);
    assertThat(latest).isPresent();
    assertThat(latest.get().id()).isEqualTo(succeededId);
  }

  private UUID createQueuedRun() {
    UUID runId = UUID.randomUUID();
    repository.createRun(
        IncidentRun.queued(
            runId,
            IncidentSource.PLAYGROUND,
            "payment-npe-01",
            "owner/demo-service",
            "deadbeef",
            true,
            Instant.now().truncatedTo(ChronoUnit.MICROS)));
    return runId;
  }
}
