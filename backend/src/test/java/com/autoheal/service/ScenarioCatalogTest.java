package com.autoheal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autoheal.domain.QueueCapacityExceededException;
import com.autoheal.domain.RunMode;
import com.autoheal.domain.ScenarioDefinition;
import com.autoheal.domain.ScenarioNotFoundException;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScenarioCatalogTest {

  private final ScenarioCatalog catalog = new ScenarioCatalog();

  @Test
  void listsAllFourDeterministicDemoScenarios() {
    List<ScenarioDefinition> scenarios = catalog.listAll();

    assertThat(scenarios)
        .extracting(ScenarioDefinition::id)
        .containsExactly(
            "payment-npe-01", "pagination-off-by-one-02", "inventory-race-03", "jpql-typo-04");
    assertThat(scenarios).allSatisfy(s -> assertThat(s.branch()).isEqualTo("scenario/" + s.id()));
    assertThat(scenarios).allSatisfy(s -> assertThat(s.language()).isEqualTo("Java 21"));
  }

  @Test
  void looksUpScenarioByIdAndThrowsTypedExceptionWhenMissing() {
    ScenarioDefinition payment = catalog.getRequired("payment-npe-01");
    assertThat(payment.branch()).isEqualTo("scenario/payment-npe-01");

    assertThatThrownBy(() -> catalog.getRequired("does-not-exist"))
        .isInstanceOf(ScenarioNotFoundException.class)
        .hasMessageContaining("does-not-exist");

    assertThat(RunMode.values()).containsExactly(RunMode.LIVE, RunMode.REPLAY);
    assertThat(new QueueCapacityExceededException(10).getMessage()).contains("10");
  }
}
