package com.autoheal.api;

import com.autoheal.service.IncidentOrchestratorService;
import com.autoheal.service.ScenarioCatalog;
import java.util.List;
import java.util.Objects;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/scenarios")
public class ScenarioController {

  private final ScenarioCatalog scenarioCatalog;
  private final IncidentOrchestratorService orchestratorService;

  public ScenarioController(
      ScenarioCatalog scenarioCatalog, IncidentOrchestratorService orchestratorService) {
    this.scenarioCatalog = Objects.requireNonNull(scenarioCatalog, "scenarioCatalog");
    this.orchestratorService = Objects.requireNonNull(orchestratorService, "orchestratorService");
  }

  @GetMapping
  public List<ScenarioResponse> listScenarios() {
    return scenarioCatalog.listAll().stream()
        .map(
            s ->
                new ScenarioResponse(
                    s.id(),
                    s.title(),
                    s.description(),
                    s.language(),
                    orchestratorService.isReplayAvailable(s.id())))
        .toList();
  }
}
