package com.autoheal.api;

import com.autoheal.domain.IncidentEvent;
import com.autoheal.domain.IncidentEventType;
import com.autoheal.domain.IncidentRun;
import com.autoheal.domain.IncidentStatus;
import com.autoheal.domain.RunMode;
import com.autoheal.service.AbuseProtectionService;
import com.autoheal.service.IncidentEventStreamService;
import com.autoheal.service.IncidentOrchestratorService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/incidents")
public class IncidentController {

  private static final Logger log = LoggerFactory.getLogger(IncidentController.class);
  private static final long HEARTBEAT_INTERVAL_SECONDS = 15L;

  private final IncidentOrchestratorService orchestratorService;
  private final IncidentEventStreamService eventStreamService;
  private final AbuseProtectionService abuseProtectionService;
  private final ObjectMapper objectMapper;
  private final ScheduledExecutorService heartbeatScheduler =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread t = new Thread(r, "sse-heartbeat");
            t.setDaemon(true);
            return t;
          });

  public IncidentController(
      IncidentOrchestratorService orchestratorService,
      IncidentEventStreamService eventStreamService,
      AbuseProtectionService abuseProtectionService,
      ObjectMapper objectMapper) {
    this.orchestratorService = Objects.requireNonNull(orchestratorService, "orchestratorService");
    this.eventStreamService = Objects.requireNonNull(eventStreamService, "eventStreamService");
    this.abuseProtectionService =
        Objects.requireNonNull(abuseProtectionService, "abuseProtectionService");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
  }

  @PostMapping("/simulate")
  public ResponseEntity<SimulateResponse> simulate(
      @RequestBody SimulateRequest request, HttpServletRequest httpRequest) {
    if (request == null || request.scenarioId() == null || request.scenarioId().isBlank()) {
      throw new IllegalArgumentException("scenarioId is required");
    }
    RunMode mode = request.mode() == null ? RunMode.LIVE : request.mode();
    if (mode == RunMode.LIVE) {
      abuseProtectionService.checkLiveRunAllowed(extractClientIp(httpRequest));
    }

    UUID runId = orchestratorService.triggerSimulation(request.scenarioId(), mode);
    SimulateResponse response =
        new SimulateResponse(
            runId, IncidentStatus.QUEUED, "/api/v1/incidents/" + runId + "/stream");
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
  }

  @GetMapping("/{runId}")
  public IncidentRunResponse getRun(@PathVariable UUID runId) {
    IncidentRun run = orchestratorService.getRun(runId);
    return IncidentRunResponse.from(run, orchestratorService.getAttempts(runId));
  }

  @GetMapping(value = "/{runId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter streamEvents(
      @PathVariable UUID runId,
      @RequestHeader(value = "Last-Event-ID", required = false) String lastEventIdHeader) {
    orchestratorService.getRun(runId);
    int lastEventId = parseLastEventId(lastEventIdHeader);

    SseEmitter emitter = new SseEmitter(0L);
    AtomicBoolean closed = new AtomicBoolean(false);
    AtomicReference<AutoCloseable> subscriptionRef = new AtomicReference<>();
    AtomicReference<ScheduledFuture<?>> heartbeatRef = new AtomicReference<>();

    Runnable cleanup =
        () -> {
          if (!closed.compareAndSet(false, true)) {
            return;
          }
          ScheduledFuture<?> heartbeat = heartbeatRef.get();
          if (heartbeat != null) {
            heartbeat.cancel(false);
          }
          AutoCloseable subscription = subscriptionRef.get();
          if (subscription != null) {
            try {
              subscription.close();
            } catch (Exception e) {
              // Top-level SSE teardown boundary: log cleanup error without leaking to servlet
              // container.
              log.warn("Failed to close event subscription for runId={}", runId, e);
            }
          }
        };

    emitter.onCompletion(cleanup);
    emitter.onTimeout(cleanup);
    emitter.onError(ignored -> cleanup.run());

    ScheduledFuture<?> heartbeat =
        heartbeatScheduler.scheduleAtFixedRate(
            () -> {
              if (closed.get()) {
                return;
              }
              try {
                emitter.send(SseEmitter.event().comment("ping"));
              } catch (IOException | IllegalStateException e) {
                cleanup.run();
              }
            },
            HEARTBEAT_INTERVAL_SECONDS,
            HEARTBEAT_INTERVAL_SECONDS,
            TimeUnit.SECONDS);
    heartbeatRef.set(heartbeat);

    AutoCloseable subscription =
        eventStreamService.subscribe(
            runId,
            lastEventId,
            event -> {
              if (closed.get()) {
                return;
              }
              sendEvent(emitter, event, cleanup);
            });
    subscriptionRef.set(subscription);

    if (closed.get()) {
      cleanup.run();
    }
    return emitter;
  }

  @PreDestroy
  void shutdownHeartbeat() {
    heartbeatScheduler.shutdownNow();
  }

  private void sendEvent(SseEmitter emitter, IncidentEvent event, Runnable cleanup) {
    try {
      JsonNode payloadNode = objectMapper.readTree(event.payloadJson());
      SseEventEnvelope envelope =
          new SseEventEnvelope(
              event.eventSeq(), event.createdAt(), event.stage(), event.eventType(), payloadNode);
      emitter.send(
          SseEmitter.event()
              .id(String.valueOf(event.eventSeq()))
              .name(event.eventType().name())
              .data(envelope, MediaType.APPLICATION_JSON));
      if (event.eventType() == IncidentEventType.COMPLETED
          || event.eventType() == IncidentEventType.FAILED) {
        cleanup.run();
        emitter.complete();
      }
    } catch (IOException e) {
      cleanup.run();
      throw new UncheckedIOException("SSE client disconnected", e);
    }
  }

  private String extractClientIp(HttpServletRequest request) {
    if (abuseProtectionService.trustForwardedFor()) {
      String forwarded = request.getHeader("X-Forwarded-For");
      if (forwarded != null && !forwarded.isBlank()) {
        int commaIdx = forwarded.indexOf(',');
        return (commaIdx > 0 ? forwarded.substring(0, commaIdx) : forwarded).trim();
      }
    }
    return request.getRemoteAddr();
  }

  private static int parseLastEventId(String header) {
    if (header == null || header.isBlank()) {
      return 0;
    }
    try {
      return Math.max(0, Integer.parseInt(header.trim()));
    } catch (NumberFormatException e) {
      return 0;
    }
  }
}
