package com.autoheal.service;

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
import com.autoheal.domain.QueueCapacityExceededException;
import com.autoheal.domain.ReplayNotAvailableException;
import com.autoheal.domain.RunMode;
import com.autoheal.domain.ScenarioDefinition;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class IncidentOrchestratorService {

  private static final Logger log = LoggerFactory.getLogger(IncidentOrchestratorService.class);

  private final ScenarioCatalog scenarioCatalog;
  private final IncidentRepository incidentRepository;
  private final IncidentEventStreamService eventStreamService;
  private final LiveIncidentExecutor liveIncidentExecutor;
  private final PipelineProperties properties;
  private final ExecutorService executorService;
  private final boolean ownsExecutor;
  private final Semaphore concurrencySemaphore;
  private final AtomicInteger queuedCount = new AtomicInteger();

  @Autowired
  public IncidentOrchestratorService(
      ScenarioCatalog scenarioCatalog,
      IncidentRepository incidentRepository,
      IncidentEventStreamService eventStreamService,
      LiveIncidentExecutor liveIncidentExecutor,
      PipelineProperties properties) {
    this(
        scenarioCatalog,
        incidentRepository,
        eventStreamService,
        liveIncidentExecutor,
        properties,
        Executors.newVirtualThreadPerTaskExecutor(),
        true);
  }

  IncidentOrchestratorService(
      ScenarioCatalog scenarioCatalog,
      IncidentRepository incidentRepository,
      IncidentEventStreamService eventStreamService,
      LiveIncidentExecutor liveIncidentExecutor,
      PipelineProperties properties,
      ExecutorService executorService) {
    this(
        scenarioCatalog,
        incidentRepository,
        eventStreamService,
        liveIncidentExecutor,
        properties,
        executorService,
        false);
  }

  private IncidentOrchestratorService(
      ScenarioCatalog scenarioCatalog,
      IncidentRepository incidentRepository,
      IncidentEventStreamService eventStreamService,
      LiveIncidentExecutor liveIncidentExecutor,
      PipelineProperties properties,
      ExecutorService executorService,
      boolean ownsExecutor) {
    this.scenarioCatalog = Objects.requireNonNull(scenarioCatalog, "scenarioCatalog");
    this.incidentRepository = Objects.requireNonNull(incidentRepository, "incidentRepository");
    this.eventStreamService = Objects.requireNonNull(eventStreamService, "eventStreamService");
    this.liveIncidentExecutor =
        Objects.requireNonNull(liveIncidentExecutor, "liveIncidentExecutor");
    this.properties = Objects.requireNonNull(properties, "properties");
    this.executorService = Objects.requireNonNull(executorService, "executorService");
    this.ownsExecutor = ownsExecutor;
    this.concurrencySemaphore = new Semaphore(properties.maxConcurrentRuns(), true);
  }

  public UUID triggerSimulation(String scenarioId, RunMode mode) {
    Objects.requireNonNull(mode, "mode must not be null");
    ScenarioDefinition scenario = scenarioCatalog.getRequired(scenarioId);
    if (mode == RunMode.REPLAY) {
      return startReplay(scenario);
    }
    return startLive(scenario);
  }

  public IncidentRun getRun(UUID runId) {
    Objects.requireNonNull(runId, "runId must not be null");
    return incidentRepository
        .findRunById(runId)
        .orElseThrow(() -> new IncidentRunNotFoundException(runId));
  }

  public List<IncidentAttempt> getAttempts(UUID runId) {
    getRun(runId);
    return incidentRepository.findAttemptsByRunId(runId);
  }

  public boolean isReplayAvailable(String scenarioId) {
    return incidentRepository.findLatestSucceededRunByScenario(scenarioId).isPresent();
  }

  @PreDestroy
  void shutdown() {
    if (ownsExecutor) {
      executorService.shutdown();
    }
  }

  private UUID startLive(ScenarioDefinition scenario) {
    int position = queuedCount.incrementAndGet();
    if (position > properties.maxQueuedRuns()) {
      queuedCount.decrementAndGet();
      throw new QueueCapacityExceededException(properties.maxQueuedRuns());
    }

    UUID runId = UUID.randomUUID();
    incidentRepository.createRun(
        IncidentRun.queued(
            runId,
            IncidentSource.PLAYGROUND,
            scenario.id(),
            properties.demoRepoName(),
            scenario.branch(),
            true,
            Instant.now()));
    eventStreamService.publish(
        runId,
        IncidentStage.QUEUED,
        IncidentEventType.STAGE_UPDATE,
        Map.of("scenarioId", scenario.id(), "queuePosition", position, "mode", "LIVE"));

    executorService.execute(() -> runWithSemaphore(runId, scenario));
    return runId;
  }

  private void runWithSemaphore(UUID runId, ScenarioDefinition scenario) {
    boolean acquired = false;
    try {
      concurrencySemaphore.acquire();
      acquired = true;
      queuedCount.decrementAndGet();
      liveIncidentExecutor.executeLiveRun(runId, scenario);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      if (!acquired) {
        queuedCount.decrementAndGet();
      }
      failRun(runId, "INTERRUPTED", "Run interrupted while waiting for sandbox slot");
    } catch (RuntimeException e) {
      // Top-level async worker boundary: mark run FAILED and emit terminal SSE event.
      log.error("Unhandled failure in live run {}", runId, e);
      failRun(
          runId, "INTERNAL_ERROR", e.getMessage() == null ? "Unexpected error" : e.getMessage());
    } finally {
      if (acquired) {
        concurrencySemaphore.release();
      }
    }
  }

  private UUID startReplay(ScenarioDefinition scenario) {
    IncidentRun sourceRun =
        incidentRepository
            .findLatestSucceededRunByScenario(scenario.id())
            .orElseThrow(() -> new ReplayNotAvailableException(scenario.id()));
    List<IncidentEvent> recordedEvents = incidentRepository.findEventsAfter(sourceRun.id(), 0);

    UUID replayRunId = UUID.randomUUID();
    Instant startedAt = Instant.now();
    incidentRepository.createRun(
        IncidentRun.queued(
            replayRunId,
            IncidentSource.REPLAY,
            scenario.id(),
            sourceRun.repoName(),
            sourceRun.commitSha(),
            false,
            startedAt));

    executorService.execute(() -> streamReplay(replayRunId, sourceRun, recordedEvents, startedAt));
    return replayRunId;
  }

  private void streamReplay(
      UUID replayRunId,
      IncidentRun sourceRun,
      List<IncidentEvent> recordedEvents,
      Instant startedAt) {
    try {
      boolean emittedTerminal = false;
      for (int i = 0; i < recordedEvents.size(); i++) {
        IncidentEvent event = recordedEvents.get(i);
        boolean isLast = (i == recordedEvents.size() - 1);
        if (properties.replayEventDelayMs() > 0 && i > 0) {
          Thread.sleep(properties.replayEventDelayMs());
        }
        if (isLast
            && (event.eventType() == IncidentEventType.COMPLETED
                || event.eventType() == IncidentEventType.FAILED)) {
          completeReplayRecord(replayRunId, sourceRun, startedAt);
          emittedTerminal = true;
        }
        eventStreamService.publishRawJson(
            replayRunId, event.stage(), event.eventType(), event.payloadJson());
      }
      if (!emittedTerminal) {
        completeReplayRecord(replayRunId, sourceRun, startedAt);
        eventStreamService.publish(
            replayRunId,
            IncidentStage.PR_OPENED,
            IncidentEventType.COMPLETED,
            Map.of("replay", true, "sourceRunId", sourceRun.id().toString()));
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      failRun(replayRunId, "INTERRUPTED", "Replay interrupted");
    } catch (RuntimeException e) {
      // Top-level async replay boundary: ensure replay run terminates cleanly.
      log.error("Unhandled failure in replay run {}", replayRunId, e);
      failRun(
          replayRunId, "REPLAY_ERROR", e.getMessage() == null ? "Replay failed" : e.getMessage());
    }
  }

  private void completeReplayRecord(UUID replayRunId, IncidentRun sourceRun, Instant startedAt) {
    Instant now = Instant.now();
    long durationMs = Math.max(0L, now.toEpochMilli() - startedAt.toEpochMilli());
    incidentRepository.completeRun(
        replayRunId,
        IncidentStatus.SUCCEEDED,
        null,
        sourceRun.attemptsUsed(),
        sourceRun.rootCauseSummary(),
        sourceRun.generatedDiff(),
        sourceRun.prUrl(),
        0,
        0,
        BigDecimal.ZERO,
        durationMs,
        now);
  }

  private void failRun(UUID runId, String reason, String message) {
    incidentRepository.completeRun(
        runId,
        IncidentStatus.FAILED,
        reason,
        0,
        null,
        null,
        null,
        0,
        0,
        BigDecimal.ZERO,
        0L,
        Instant.now());
    eventStreamService.publish(
        runId,
        IncidentStage.INCIDENT_RECEIVED,
        IncidentEventType.FAILED,
        Map.of("reason", reason, "message", message));
  }
}
