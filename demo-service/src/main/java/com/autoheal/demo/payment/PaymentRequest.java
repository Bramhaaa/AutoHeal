package com.autoheal.demo.payment;

import java.math.BigDecimal;

public record PaymentRequest(BigDecimal subtotal, Discount discount) {}
