package com.autoheal.domain;

import java.math.BigDecimal;

public class DailyBudgetExceededException extends RuntimeException {

  public DailyBudgetExceededException(BigDecimal dailyBudgetUsd) {
    super(
        "Daily LLM spend limit ($"
            + dailyBudgetUsd.toPlainString()
            + ") reached. Try REPLAY mode instead.");
  }
}
