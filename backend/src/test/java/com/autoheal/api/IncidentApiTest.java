package com.autoheal.api;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.autoheal.PostgresTestContainer;
import com.autoheal.domain.IncidentEventType;
import com.autoheal.domain.IncidentRepository;
import com.autoheal.domain.IncidentRun;
import com.autoheal.domain.IncidentSource;
import com.autoheal.domain.IncidentStage;
import com.autoheal.domain.IncidentStatus;
import com.autoheal.domain.LiveIncidentExecutor;
import com.autoheal.service.IncidentEventStreamService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "autoheal.pipeline.replay-event-delay-ms=0")
@AutoConfigureMockMvc
@Import(PostgresTestContainer.class)
@MockitoBean(types = LiveIncidentExecutor.class)
class IncidentApiTest {

  private final MockMvc mockMvc;
  private final IncidentRepository incidentRepository;
  private final IncidentEventStreamService eventStreamService;

  @Autowired
  IncidentApiTest(
      MockMvc mockMvc,
      IncidentRepository incidentRepository,
      IncidentEventStreamService eventStreamService) {
    this.mockMvc = mockMvc;
    this.incidentRepository = incidentRepository;
    this.eventStreamService = eventStreamService;
  }

  @Test
  void listsAllFourScenarios() throws Exception {
    mockMvc
        .perform(get("/api/v1/scenarios"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(4)))
        .andExpect(jsonPath("$[0].id", is("payment-npe-01")))
        .andExpect(jsonPath("$[0].language", is("Java 21")));
  }

  @Test
  void simulatesLiveRunReturns202AndEnforcesPerIpRateLimitWith429SuggestedReplay()
      throws Exception {
    String body =
        """
        {"scenarioId":"payment-npe-01","mode":"LIVE"}
        """;

    for (int i = 0; i < 3; i++) {
      mockMvc
          .perform(
              post("/api/v1/incidents/simulate")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body)
                  .with(
                      req -> {
                        req.setRemoteAddr("192.0.2.55");
                        return req;
                      }))
          .andExpect(status().isAccepted())
          .andExpect(jsonPath("$.status", is("QUEUED")))
          .andExpect(jsonPath("$.streamUrl", startsWith("/api/v1/incidents/")));
    }

    // 4th live request from same IP hits Bucket4j limit -> 429 suggesting REPLAY
    mockMvc
        .perform(
            post("/api/v1/incidents/simulate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .with(
                    req -> {
                      req.setRemoteAddr("192.0.2.55");
                      return req;
                    }))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.suggestedMode", is("REPLAY")));
  }

  @Test
  void returns404ForUnknownScenarioAndUnknownRunAnd409WhenReplayUnavailable() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/incidents/simulate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"scenarioId":"no-such-scenario","mode":"REPLAY"}
                    """))
        .andExpect(status().isNotFound());

    mockMvc
        .perform(
            post("/api/v1/incidents/simulate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"scenarioId":"inventory-race-03","mode":"REPLAY"}
                    """))
        .andExpect(status().isConflict());

    mockMvc.perform(get("/api/v1/incidents/" + UUID.randomUUID())).andExpect(status().isNotFound());
  }

  @Test
  void returnsRunDetailsAndReplaysEventsOverSseStream() throws Exception {
    UUID runId = UUID.randomUUID();
    incidentRepository.createRun(
        IncidentRun.queued(
            runId,
            IncidentSource.PLAYGROUND,
            "jpql-typo-04",
            "owner/demo",
            "sha-123",
            true,
            Instant.now()));
    eventStreamService.publish(
        runId, IncidentStage.LOG_DISTILLED, IncidentEventType.STAGE_UPDATE, Map.of("step", 1));
    incidentRepository.completeRun(
        runId,
        IncidentStatus.SUCCEEDED,
        null,
        1,
        "Fixed JPQL typo",
        "--- diff ---",
        "https://github.com/owner/demo/pull/42",
        500,
        120,
        new BigDecimal("0.000147"),
        5400L,
        Instant.now());
    eventStreamService.publish(
        runId,
        IncidentStage.PR_OPENED,
        IncidentEventType.COMPLETED,
        Map.of("prUrl", "https://github.com/owner/demo/pull/42"));

    mockMvc
        .perform(get("/api/v1/incidents/" + runId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.runId", is(runId.toString())))
        .andExpect(jsonPath("$.status", is("SUCCEEDED")))
        .andExpect(jsonPath("$.rootCauseSummary", is("Fixed JPQL typo")))
        .andExpect(jsonPath("$.prUrl", is("https://github.com/owner/demo/pull/42")));

    // SSE stream with Last-Event-ID: 1 replays event 2 (COMPLETED) and closes cleanly
    mockMvc
        .perform(get("/api/v1/incidents/" + runId + "/stream").header("Last-Event-ID", "1"))
        .andExpect(status().isOk());
  }
}
