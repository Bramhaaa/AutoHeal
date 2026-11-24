package com.autoheal.service;

import com.autoheal.domain.ScenarioDefinition;
import com.autoheal.domain.ScenarioNotFoundException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class ScenarioCatalog {

  private final Map<String, ScenarioDefinition> scenariosById;

  public ScenarioCatalog() {
    List<ScenarioDefinition> defaults =
        List.of(
            new ScenarioDefinition(
                "payment-npe-01",
                "Payment Service NullPointerException",
                "Missing null check when an optional discount payload is omitted.",
                "Java 21",
                "scenario/payment-npe-01",
                "com.autoheal.demo.payment.PaymentServiceTest",
                "returnsSubtotalWhenDiscountIsNull"),
            new ScenarioDefinition(
                "pagination-off-by-one-02",
                "Off-by-One Pagination Boundary Bug",
                "Slice end index drops the final item on full pages.",
                "Java 21",
                "scenario/pagination-off-by-one-02",
                "com.autoheal.demo.pagination.PaginationServiceTest",
                "returnsExactPageSizeForFullPage"),
            new ScenarioDefinition(
                "inventory-race-03",
                "Concurrency Race Condition",
                "Unsynchronized stock decrement loses updates under concurrent threads.",
                "Java 21",
                "scenario/inventory-race-03",
                "com.autoheal.demo.inventory.InventoryServiceTest",
                "decrementsStockAtomicallyUnderConcurrentLoad"),
            new ScenarioDefinition(
                "jpql-typo-04",
                "Repository JPQL Query Typo",
                "Broken JPQL entity property name fails Spring Data JPA query validation.",
                "Java 21",
                "scenario/jpql-typo-04",
                "com.autoheal.demo.order.CustomerOrderRepositoryTest",
                "findsOrdersMatchingOrderStatus"));

    Map<String, ScenarioDefinition> index = new LinkedHashMap<>();
    for (ScenarioDefinition scenario : defaults) {
      index.put(scenario.id(), scenario);
    }
    this.scenariosById = Collections.unmodifiableMap(index);
  }

  public List<ScenarioDefinition> listAll() {
    return List.copyOf(scenariosById.values());
  }

  public ScenarioDefinition getRequired(String scenarioId) {
    Objects.requireNonNull(scenarioId, "scenarioId must not be null");
    ScenarioDefinition scenario = scenariosById.get(scenarioId);
    if (scenario == null) {
      throw new ScenarioNotFoundException(scenarioId);
    }
    return scenario;
  }
}
