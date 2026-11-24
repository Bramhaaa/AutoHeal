package com.autoheal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autoheal.domain.IncidentAttempt;
import com.autoheal.domain.IncidentEvent;
import com.autoheal.domain.IncidentEventType;
import com.autoheal.domain.IncidentRepository;
import com.autoheal.domain.IncidentRun;
import com.autoheal.domain.IncidentRunNotFoundException;
import com.autoheal.domain.IncidentSource;
import com.autoheal.domain.IncidentStage;
import com.autoheal.domain.IncidentStatus;
import com.autoheal.domain.LiveIncidentExecutor;
import com.autoheal.domain.LlmCallRecord;
import com.autoheal.domain.QueueCapacityExceededException;
import com.autoheal.domain.ReplayNotAvailableException;
import com.autoheal.domain.RunMode;
import com.autoheal.domain.ScenarioDefinition;
import com.autoheal.domain.WebhookFailureEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;

class IncidentOrchestratorServiceTest {

  @Test
  void boundsConcurrentLiveRunsWithSemaphoreAndCompletesAllSubmittedRuns() throws Exception {
    InMemoryIncidentRepository repo = new InMemoryIncidentRepository();
    IncidentEventStreamService stream = new IncidentEventStreamService(repo, new ObjectMapper());
    PipelineProperties props = new PipelineProperties(2, 10, 0L, "", "owner/demo-service", false);

    AtomicInteger active = new AtomicInteger();
    AtomicInteger maxActive = new AtomicInteger();
    CountDownLatch twoRunning = new CountDownLatch(2);
    CountDownLatch releaseWorkers = new CountDownLatch(1);
    CountDownLatch allDone = new CountDownLatch(3);

    LiveIncidentExecutor executor =
        liveOnly(
            (runId, scenario) -> {
              int current = active.incrementAndGet();
              maxActive.accumulateAndGet(current, Math::max);
              twoRunning.countDown();
              try {
                releaseWorkers.await(5, TimeUnit.SECONDS);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              } finally {
                active.decrementAndGet();
                allDone.countDown();
              }
            });

    try (ExecutorService vthreads = Executors.newVirtualThreadPerTaskExecutor()) {
      IncidentOrchestratorService orchestrator =
          new IncidentOrchestratorService(
              new ScenarioCatalog(), repo, stream, executor, props, vthreads);

      UUID r1 = orchestrator.triggerSimulation("payment-npe-01", RunMode.LIVE);
      UUID r2 = orchestrator.triggerSimulation("pagination-off-by-one-02", RunMode.LIVE);
      UUID r3 = orchestrator.triggerSimulation("inventory-race-03", RunMode.LIVE);

      assertThat(twoRunning.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(maxActive.get()).isEqualTo(2);

      releaseWorkers.countDown();
      assertThat(allDone.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(repo.findRunById(r1)).isPresent();
      assertThat(repo.findRunById(r2)).isPresent();
      assertThat(repo.findRunById(r3)).isPresent();
    }
  }

  @Test
  void rejectsLiveRunWhenQueueCapacityExceeded() throws Exception {
    InMemoryIncidentRepository repo = new InMemoryIncidentRepository();
    IncidentEventStreamService stream = new IncidentEventStreamService(repo, new ObjectMapper());
    // 1 active + 1 queued max
    PipelineProperties props = new PipelineProperties(1, 1, 0L, "", "owner/demo-service", false);

    CountDownLatch firstStarted = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    LiveIncidentExecutor blockingExecutor =
        liveOnly(
            (runId, scenario) -> {
              firstStarted.countDown();
              try {
                release.await(5, TimeUnit.SECONDS);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            });

    try (ExecutorService vthreads = Executors.newVirtualThreadPerTaskExecutor()) {
      IncidentOrchestratorService orchestrator =
          new IncidentOrchestratorService(
              new ScenarioCatalog(), repo, stream, blockingExecutor, props, vthreads);

      orchestrator.triggerSimulation("payment-npe-01", RunMode.LIVE);
      assertThat(firstStarted.await(5, TimeUnit.SECONDS)).isTrue();

      // Fills the single queue slot
      orchestrator.triggerSimulation("pagination-off-by-one-02", RunMode.LIVE);

      // Next live submission must fail fast with QueueCapacityExceededException
      assertThatThrownBy(() -> orchestrator.triggerSimulation("inventory-race-03", RunMode.LIVE))
          .isInstanceOf(QueueCapacityExceededException.class);

      release.countDown();
    }
  }

  @Test
  void replayModeStreamsRecordedEventsWithZeroCostAndMarksSourceReplay() throws Exception {
    InMemoryIncidentRepository repo = new InMemoryIncidentRepository();
    IncidentEventStreamService stream = new IncidentEventStreamService(repo, new ObjectMapper());
    PipelineProperties props = new PipelineProperties(3, 10, 0L, "", "owner/demo-service", false);

    // Seed a real succeeded run with recorded events
    UUID recordedRunId = UUID.randomUUID();
    repo.createRun(
        IncidentRun.queued(
            recordedRunId,
            IncidentSource.PLAYGROUND,
            "payment-npe-01",
            "owner/demo-service",
            "abc123",
            true,
            Instant.now()));
    stream.publish(
        recordedRunId, IncidentStage.LOG_DISTILLED, IncidentEventType.STAGE_UPDATE, Map.of("k", 1));
    stream.publish(
        recordedRunId,
        IncidentStage.PR_OPENED,
        IncidentEventType.COMPLETED,
        Map.of("prUrl", "https://github.com/owner/demo/pull/9"));
    repo.completeRun(
        recordedRunId,
        IncidentStatus.SUCCEEDED,
        null,
        1,
        "Fixed null discount check",
        "--- diff ---",
        "https://github.com/owner/demo/pull/9",
        1000,
        200,
        new BigDecimal("0.000270"),
        12_000L,
        Instant.now());

    CountDownLatch completedLatch = new CountDownLatch(1);
    try (ExecutorService vthreads = Executors.newVirtualThreadPerTaskExecutor()) {
      IncidentOrchestratorService orchestrator =
          new IncidentOrchestratorService(
              new ScenarioCatalog(),
              repo,
              stream,
              liveOnly(
                  (runId, scenario) -> {
                    throw new AssertionError("Live executor must not run in REPLAY mode");
                  }),
              props,
              vthreads);

      assertThat(orchestrator.isReplayAvailable("payment-npe-01")).isTrue();
      assertThat(orchestrator.isReplayAvailable("jpql-typo-04")).isFalse();

      UUID replayRunId = orchestrator.triggerSimulation("payment-npe-01", RunMode.REPLAY);
      try (AutoCloseable ignored =
          stream.subscribe(
              replayRunId,
              0,
              event -> {
                if (event.eventType() == IncidentEventType.COMPLETED) {
                  completedLatch.countDown();
                }
              })) {
        assertThat(completedLatch.await(5, TimeUnit.SECONDS)).isTrue();
      }

      IncidentRun replayRun = orchestrator.getRun(replayRunId);
      assertThat(replayRun.source()).isEqualTo(IncidentSource.REPLAY);
      assertThat(replayRun.status()).isEqualTo(IncidentStatus.SUCCEEDED);
      assertThat(replayRun.estimatedCostUsd()).isEqualByComparingTo(BigDecimal.ZERO);
      assertThat(replayRun.rootCauseSummary()).isEqualTo("Fixed null discount check");
      assertThat(repo.findEventsAfter(replayRunId, 0)).hasSize(2);
    }
  }

  @Test
  void throwsTypedExceptionsWhenReplayOrRunNotFound() {
    InMemoryIncidentRepository repo = new InMemoryIncidentRepository();
    IncidentEventStreamService stream = new IncidentEventStreamService(repo, new ObjectMapper());
    PipelineProperties props = new PipelineProperties(3, 10, 0L, "", "owner/demo-service", false);

    try (ExecutorService vthreads = Executors.newVirtualThreadPerTaskExecutor()) {
      IncidentOrchestratorService orchestrator =
          new IncidentOrchestratorService(
              new ScenarioCatalog(), repo, stream, liveOnly((r, s) -> {}), props, vthreads);

      assertThatThrownBy(() -> orchestrator.triggerSimulation("jpql-typo-04", RunMode.REPLAY))
          .isInstanceOf(ReplayNotAvailableException.class);
      assertThatThrownBy(() -> orchestrator.getRun(UUID.randomUUID()))
          .isInstanceOf(IncidentRunNotFoundException.class);
    }
  }

  @Test
  void marksRunFailedAndReleasesSemaphoreWhenLiveExecutorThrows() throws Exception {
    InMemoryIncidentRepository repo = new InMemoryIncidentRepository();
    IncidentEventStreamService stream = new IncidentEventStreamService(repo, new ObjectMapper());
    PipelineProperties props = new PipelineProperties(1, 5, 0L, "", "owner/demo-service", false);

    CountDownLatch failedLatch = new CountDownLatch(1);
    CountDownLatch secondRanLatch = new CountDownLatch(1);
    AtomicInteger calls = new AtomicInteger();

    LiveIncidentExecutor flakyExecutor =
        liveOnly(
            (runId, scenario) -> {
              if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("docker daemon unreachable");
              }
              secondRanLatch.countDown();
            });

    try (ExecutorService vthreads = Executors.newVirtualThreadPerTaskExecutor()) {
      IncidentOrchestratorService orchestrator =
          new IncidentOrchestratorService(
              new ScenarioCatalog(), repo, stream, flakyExecutor, props, vthreads);

      UUID failedRunId = orchestrator.triggerSimulation("payment-npe-01", RunMode.LIVE);
      try (AutoCloseable ignored =
          stream.subscribe(
              failedRunId,
              0,
              event -> {
                if (event.eventType() == IncidentEventType.FAILED) {
                  failedLatch.countDown();
                }
              })) {
        assertThat(failedLatch.await(5, TimeUnit.SECONDS)).isTrue();
      }

      IncidentRun failedRun = orchestrator.getRun(failedRunId);
      assertThat(failedRun.status()).isEqualTo(IncidentStatus.FAILED);
      assertThat(failedRun.failureReason()).isEqualTo("INTERNAL_ERROR");
      assertThat(orchestrator.getAttempts(failedRunId)).isEmpty();

      // Semaphore must have been released so a subsequent run executes immediately
      orchestrator.triggerSimulation("jpql-typo-04", RunMode.LIVE);
      assertThat(secondRanLatch.await(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  void triggersWebhookRunAndEnforcesQueueCapacity() throws Exception {
    InMemoryIncidentRepository repo = new InMemoryIncidentRepository();
    IncidentEventStreamService stream = new IncidentEventStreamService(repo, new ObjectMapper());
    PipelineProperties props = new PipelineProperties(1, 1, 0L, "", "owner/demo-service", false);

    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch completed = new CountDownLatch(2);

    LiveIncidentExecutor webhookExecutor =
        new LiveIncidentExecutor() {
          @Override
          public void executeLiveRun(UUID runId, ScenarioDefinition scenario) {}

          @Override
          public void executeWebhookRun(UUID runId, WebhookFailureEvent event) {
            started.countDown();
            try {
              release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            } finally {
              completed.countDown();
            }
          }
        };

    WebhookFailureEvent event =
        new WebhookFailureEvent(
            "del-1", "acme", "demo", "https://github.com/acme/demo.git", "main", "sha1", 100L);

    try (ExecutorService vthreads = Executors.newVirtualThreadPerTaskExecutor()) {
      IncidentOrchestratorService orchestrator =
          new IncidentOrchestratorService(
              new ScenarioCatalog(), repo, stream, webhookExecutor, props, vthreads);

      UUID firstRunId = orchestrator.triggerWebhookRun(event);
      assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(orchestrator.getRun(firstRunId).source()).isEqualTo(IncidentSource.WEBHOOK);

      orchestrator.triggerWebhookRun(event);
      assertThatThrownBy(() -> orchestrator.triggerWebhookRun(event))
          .isInstanceOf(QueueCapacityExceededException.class);

      release.countDown();
      assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  private static LiveIncidentExecutor liveOnly(BiConsumer<UUID, ScenarioDefinition> handler) {
    return new LiveIncidentExecutor() {
      @Override
      public void executeLiveRun(UUID runId, ScenarioDefinition scenario) {
        handler.accept(runId, scenario);
      }

      @Override
      public void executeWebhookRun(UUID runId, WebhookFailureEvent event) {}
    };
  }

  private static final class InMemoryIncidentRepository implements IncidentRepository {

    private final Map<UUID, IncidentRun> runs = new ConcurrentHashMap<>();
    private final Map<UUID, List<IncidentAttempt>> attempts = new ConcurrentHashMap<>();
    private final Map<UUID, List<IncidentEvent>> events = new ConcurrentHashMap<>();

    @Override
    public IncidentRun createRun(IncidentRun run) {
      runs.put(run.id(), run);
      return run;
    }

    @Override
    public Optional<IncidentRun> findRunById(UUID runId) {
      return Optional.ofNullable(runs.get(runId));
    }

    @Override
    public Optional<IncidentRun> findLatestSucceededRunByScenario(String scenarioId) {
      return runs.values().stream()
          .filter(
              r ->
                  scenarioId.equals(r.scenarioId())
                      && r.status() == IncidentStatus.SUCCEEDED
                      && r.source() != IncidentSource.REPLAY)
          .max(Comparator.comparing(IncidentRun::createdAt));
    }

    @Override
    public void updateRunStatus(UUID runId, IncidentStatus status) {
      runs.computeIfPresent(
          runId,
          (id, r) ->
              new IncidentRun(
                  r.id(),
                  r.source(),
                  r.scenarioId(),
                  r.repoName(),
                  r.commitSha(),
                  status,
                  r.failureReason(),
                  r.attemptsUsed(),
                  r.ragEnabled(),
                  r.rootCauseSummary(),
                  r.generatedDiff(),
                  r.prUrl(),
                  r.totalPromptTokens(),
                  r.totalCompletionTokens(),
                  r.estimatedCostUsd(),
                  r.durationMs(),
                  r.createdAt(),
                  r.completedAt()));
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
      runs.computeIfPresent(
          runId,
          (id, r) ->
              new IncidentRun(
                  r.id(),
                  r.source(),
                  r.scenarioId(),
                  r.repoName(),
                  r.commitSha(),
                  status,
                  failureReason,
                  attemptsUsed,
                  r.ragEnabled(),
                  rootCauseSummary,
                  generatedDiff,
                  prUrl,
                  totalPromptTokens,
                  totalCompletionTokens,
                  estimatedCostUsd,
                  durationMs,
                  r.createdAt(),
                  completedAt));
    }

    @Override
    public void saveAttempt(IncidentAttempt attempt) {
      attempts.computeIfAbsent(attempt.runId(), ignored -> new ArrayList<>()).add(attempt);
    }

    @Override
    public List<IncidentAttempt> findAttemptsByRunId(UUID runId) {
      return List.copyOf(attempts.getOrDefault(runId, List.of()));
    }

    @Override
    public void saveLlmCall(LlmCallRecord call) {}

    @Override
    public BigDecimal sumDailyLlmCostUsd(Instant dayStartInclusive, Instant dayEndExclusive) {
      return BigDecimal.ZERO;
    }

    @Override
    public IncidentEvent appendEvent(
        UUID runId, IncidentStage stage, IncidentEventType eventType, String payloadJson) {
      List<IncidentEvent> list = events.computeIfAbsent(runId, ignored -> new ArrayList<>());
      synchronized (list) {
        int nextSeq = list.size() + 1;
        IncidentEvent event =
            new IncidentEvent(runId, nextSeq, stage, eventType, payloadJson, Instant.now());
        list.add(event);
        return event;
      }
    }

    @Override
    public List<IncidentEvent> findEventsAfter(UUID runId, int afterEventSeq) {
      List<IncidentEvent> list = events.getOrDefault(runId, List.of());
      synchronized (list) {
        return list.stream().filter(e -> e.eventSeq() > afterEventSeq).toList();
      }
    }
  }
}
