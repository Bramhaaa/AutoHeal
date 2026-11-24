package com.autoheal.service;

import com.autoheal.domain.IncidentEvent;
import com.autoheal.domain.IncidentEventType;
import com.autoheal.domain.IncidentRepository;
import com.autoheal.domain.IncidentStage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class IncidentEventStreamService {

  private static final Logger log = LoggerFactory.getLogger(IncidentEventStreamService.class);

  private final IncidentRepository incidentRepository;
  private final ObjectMapper objectMapper;
  private final ConcurrentHashMap<UUID, CopyOnWriteArrayList<OrderedSubscriber>> subscribers =
      new ConcurrentHashMap<>();

  public IncidentEventStreamService(
      IncidentRepository incidentRepository, ObjectMapper objectMapper) {
    this.incidentRepository =
        Objects.requireNonNull(incidentRepository, "incidentRepository must not be null");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
  }

  public IncidentEvent publish(
      UUID runId, IncidentStage stage, IncidentEventType eventType, Map<String, Object> payload) {
    Objects.requireNonNull(runId, "runId must not be null");
    Objects.requireNonNull(stage, "stage must not be null");
    Objects.requireNonNull(eventType, "eventType must not be null");
    Objects.requireNonNull(payload, "payload must not be null");

    String payloadJson = serializePayload(payload);
    IncidentEvent saved = incidentRepository.appendEvent(runId, stage, eventType, payloadJson);
    dispatchLive(saved);

    if (isTerminal(eventType)) {
      subscribers.remove(runId);
    }
    return saved;
  }

  public AutoCloseable subscribe(UUID runId, int lastEventId, Consumer<IncidentEvent> onEvent) {
    Objects.requireNonNull(runId, "runId must not be null");
    Objects.requireNonNull(onEvent, "onEvent must not be null");

    OrderedSubscriber subscriber = new OrderedSubscriber(onEvent, Math.max(0, lastEventId));
    CopyOnWriteArrayList<OrderedSubscriber> runSubscribers =
        subscribers.computeIfAbsent(runId, ignored -> new CopyOnWriteArrayList<>());
    runSubscribers.add(subscriber);

    List<IncidentEvent> historical = incidentRepository.findEventsAfter(runId, lastEventId);
    for (IncidentEvent event : historical) {
      if (!deliverSafely(runSubscribers, subscriber, event)) {
        break;
      }
    }

    return () -> {
      runSubscribers.remove(subscriber);
      if (runSubscribers.isEmpty()) {
        subscribers.remove(runId, runSubscribers);
      }
    };
  }

  public int activeSubscriberCount(UUID runId) {
    CopyOnWriteArrayList<OrderedSubscriber> list = subscribers.get(runId);
    return list == null ? 0 : list.size();
  }

  private void dispatchLive(IncidentEvent event) {
    CopyOnWriteArrayList<OrderedSubscriber> runSubscribers = subscribers.get(event.runId());
    if (runSubscribers == null || runSubscribers.isEmpty()) {
      return;
    }
    for (OrderedSubscriber subscriber : runSubscribers) {
      deliverSafely(runSubscribers, subscriber, event);
    }
  }

  private boolean deliverSafely(
      CopyOnWriteArrayList<OrderedSubscriber> runSubscribers,
      OrderedSubscriber subscriber,
      IncidentEvent event) {
    try {
      subscriber.deliver(event);
      return true;
    } catch (RuntimeException e) {
      // Top-level subscriber boundary: disconnect failing SSE client without affecting other
      // listeners.
      log.warn(
          "Removing failing event subscriber for runId={} at seq={}: {}",
          event.runId(),
          event.eventSeq(),
          e.getMessage());
      runSubscribers.remove(subscriber);
      return false;
    }
  }

  private String serializePayload(Map<String, Object> payload) {
    try {
      return objectMapper.writeValueAsString(payload);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Failed to serialize incident event payload", e);
    }
  }

  private static boolean isTerminal(IncidentEventType eventType) {
    return eventType == IncidentEventType.COMPLETED || eventType == IncidentEventType.FAILED;
  }

  private static final class OrderedSubscriber {
    private final Consumer<IncidentEvent> delegate;
    private int lastDeliveredSeq;

    private OrderedSubscriber(Consumer<IncidentEvent> delegate, int initialSeq) {
      this.delegate = delegate;
      this.lastDeliveredSeq = initialSeq;
    }

    private synchronized void deliver(IncidentEvent event) {
      if (event.eventSeq() <= lastDeliveredSeq) {
        return;
      }
      delegate.accept(event);
      lastDeliveredSeq = event.eventSeq();
    }
  }
}
