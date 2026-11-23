package com.autoheal.demo.payment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Service;

@Service
public class PaymentService {

  private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

  public BigDecimal calculateTotal(PaymentRequest request) {
    BigDecimal subtotal = request.subtotal();
    if (request.discount() == null || request.discount().percentage() == null) {
      return subtotal.setScale(2, RoundingMode.HALF_UP);
    }
    BigDecimal discountAmount =
        subtotal.multiply(request.discount().percentage()).divide(ONE_HUNDRED, 2, RoundingMode.HALF_UP);
    return subtotal.subtract(discountAmount).setScale(2, RoundingMode.HALF_UP);
  }
}
