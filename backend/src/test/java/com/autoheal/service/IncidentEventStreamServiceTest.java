package com.autoheal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.autoheal.domain.IncidentAttempt;
import com.autoheal.domain.IncidentEvent;
import com.autoheal.domain.IncidentEventType;
import com.autoheal.domain.IncidentRepository;
import com.autoheal.domain.IncidentRun;
import com.autoheal.domain.IncidentStage;
import com.autoheal.domain.IncidentStatus;
import com.autoheal.domain.LlmCallRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class IncidentEventStreamServiceTest {

  @Test
  void publishesPersistedEventsToActiveSubscribersAndCleansUpOnTerminalEvent() throws Exception {
    InMemoryEventRepository repo = new InMemoryEventRepository();
    IncidentEventStreamService streamService =
        new IncidentEventStreamService(repo, new ObjectMapper());
    UUID runId = UUID.randomUUID();
    List<IncidentEvent> received = new CopyOnWriteArrayList<>();

    try (AutoCloseable ignored = streamService.subscribe(runId, 0, received::add)) {
      streamService.publish(
          runId, IncidentStage.QUEUED, IncidentEventType.STAGE_UPDATE, Map.of("position", 0));
      streamService.publish(
          runId,
          IncidentStage.SANDBOX_VERIFY,
          IncidentEventType.LOG_LINE,
          Map.of("message", "[INFO] Tests run: 12, Failures: 0"));
      streamService.publish(
          runId,
          IncidentStage.PR_OPENED,
          IncidentEventType.COMPLETED,
          Map.of("prUrl", "https://example.test/pr/1"));
    }

    assertThat(received).extracting(IncidentEvent::eventSeq).containsExactly(1, 2, 3);
    assertThat(received.get(0).stage()).isEqualTo(IncidentStage.QUEUED);
    assertThat(received.get(1).payloadJson()).contains("Tests run: 12");
    assertThat(streamService.activeSubscriberCount(runId)).isZero();
  }

  @Test
  void replaysPersistedEventsAfterLastEventIdAndStreamsSubsequentLiveEventsWithoutDuplicates()
      throws Exception {
    InMemoryEventRepository repo = new InMemoryEventRepository();
    IncidentEventStreamService streamService =
        new IncidentEventStreamService(repo, new ObjectMapper());
    UUID runId = UUID.randomUUID();

    // Events 1 and 2 happen before client reconnects with Last-Event-ID: 1
    streamService.publish(
        runId, IncidentStage.QUEUED, IncidentEventType.STAGE_UPDATE, Map.of("step", 1));
    streamService.publish(
        runId, IncidentStage.LOG_DISTILLED, IncidentEventType.STAGE_UPDATE, Map.of("step", 2));

    List<IncidentEvent> received = new CopyOnWriteArrayList<>();
    try (AutoCloseable ignored = streamService.subscribe(runId, 1, received::add)) {
      // Event 3 arrives live after reconnect
      streamService.publish(
          runId,
          IncidentStage.AGENT_PATCHING,
          IncidentEventType.TOOL_CALL,
          Map.of("tool", "read_file"));
    }

    assertThat(received).extracting(IncidentEvent::eventSeq).containsExactly(2, 3);
    assertThat(received.get(0).stage()).isEqualTo(IncidentStage.LOG_DISTILLED);
    assertThat(received.get(1).eventType()).isEqualTo(IncidentEventType.TOOL_CALL);
  }

  @Test
  void isolatesSubscriberFailuresSoOtherSubscribersStillReceiveEvents() throws Exception {
    InMemoryEventRepository repo = new InMemoryEventRepository();
    IncidentEventStreamService streamService =
        new IncidentEventStreamService(repo, new ObjectMapper());
    UUID runId = UUID.randomUUID();
    List<IncidentEvent> healthyReceived = new CopyOnWriteArrayList<>();

    try (AutoCloseable broken =
            streamService.subscribe(
                runId,
                0,
                event -> {
                  throw new IllegalStateException("client disconnected");
                });
        AutoCloseable healthy = streamService.subscribe(runId, 0, healthyReceived::add)) {
      streamService.publish(
          runId, IncidentStage.QUEUED, IncidentEventType.STAGE_UPDATE, Map.of("ok", true));
    }

    assertThat(healthyReceived).hasSize(1);
    assertThat(healthyReceived.get(0).eventSeq()).isEqualTo(1);
  }

  private static final class InMemoryEventRepository implements IncidentRepository {

    private final List<IncidentEvent> events = Collections.synchronizedList(new ArrayList<>());

    @Override
    public IncidentEvent appendEvent(
        UUID runId, IncidentStage stage, IncidentEventType eventType, String payloadJson) {
      synchronized (events) {
        int nextSeq = (int) events.stream().filter(e -> e.runId().equals(runId)).count() + 1;
        IncidentEvent event =
            new IncidentEvent(runId, nextSeq, stage, eventType, payloadJson, Instant.now());
        events.add(event);
        return event;
      }
    }

    @Override
    public List<IncidentEvent> findEventsAfter(UUID runId, int afterEventSeq) {
      synchronized (events) {
        return events.stream()
            .filter(e -> e.runId().equals(runId) && e.eventSeq() > afterEventSeq)
            .toList();
      }
    }

    @Override
    public IncidentRun createRun(IncidentRun run) {
      return run;
    }

    @Override
    public Optional<IncidentRun> findRunById(UUID runId) {
      return Optional.empty();
    }

    @Override
    public Optional<IncidentRun> findLatestSucceededRunByScenario(String scenarioId) {
      return Optional.empty();
    }

    @Override
    public void updateRunStatus(UUID runId, IncidentStatus status) {}

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
        Instant completedAt) {}

    @Override
    public void saveAttempt(IncidentAttempt attempt) {}

    @Override
    public List<IncidentAttempt> findAttemptsByRunId(UUID runId) {
      return List.of();
    }

    @Override
    public void saveLlmCall(LlmCallRecord call) {}

    @Override
    public BigDecimal sumDailyLlmCostUsd(Instant dayStartInclusive, Instant dayEndExclusive) {
      return BigDecimal.ZERO;
    }
  }
}
