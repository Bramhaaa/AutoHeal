package com.autoheal.domain;

public class WebhookSignatureException extends RuntimeException {

  public WebhookSignatureException(String message) {
    super(message);
  }
}
