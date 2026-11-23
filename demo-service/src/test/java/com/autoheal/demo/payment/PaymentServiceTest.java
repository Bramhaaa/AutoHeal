package com.autoheal.demo.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class PaymentServiceTest {

  private final PaymentService paymentService = new PaymentService();

  @Test
  void appliesPercentageDiscountWhenPresent() {
    PaymentRequest request =
        new PaymentRequest(new BigDecimal("100.00"), new Discount("SAVE10", new BigDecimal("10")));

    assertThat(paymentService.calculateTotal(request)).isEqualByComparingTo("90.00");
  }

  @Test
  void returnsSubtotalWhenDiscountIsNull() {
    PaymentRequest request = new PaymentRequest(new BigDecimal("49.99"), null);

    assertThat(paymentService.calculateTotal(request)).isEqualByComparingTo("49.99");
  }
}
