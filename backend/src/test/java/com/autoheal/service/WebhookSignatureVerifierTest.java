package com.autoheal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autoheal.domain.WebhookSignatureException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class WebhookSignatureVerifierTest {

  private static final String SECRET = "super-secret-webhook-key";
  private final WebhookSignatureVerifier verifier =
      new WebhookSignatureVerifier(new WebhookProperties(SECRET, 1000));

  @Test
  void acceptsValidHmacSha256Signature() {
    byte[] payload = "{\"action\":\"completed\"}".getBytes(StandardCharsets.UTF_8);
    String validHeader = WebhookSignatureVerifier.computeSignatureHeader(SECRET, payload);

    assertThatCode(() -> verifier.verify(payload, validHeader)).doesNotThrowAnyException();
  }

  @Test
  void rejectsMissingOrBlankSignatureHeader() {
    byte[] payload = "{}".getBytes(StandardCharsets.UTF_8);

    assertThatThrownBy(() -> verifier.verify(payload, null))
        .isInstanceOf(WebhookSignatureException.class);
    assertThatThrownBy(() -> verifier.verify(payload, "   "))
        .isInstanceOf(WebhookSignatureException.class);
  }

  @Test
  void rejectsWrongPrefixOrMalformedHex() {
    byte[] payload = "{}".getBytes(StandardCharsets.UTF_8);

    assertThatThrownBy(() -> verifier.verify(payload, "sha1=abcdef"))
        .isInstanceOf(WebhookSignatureException.class);
    assertThatThrownBy(() -> verifier.verify(payload, "sha256=not-hex-zzzz"))
        .isInstanceOf(WebhookSignatureException.class);
    assertThatThrownBy(() -> verifier.verify(payload, "sha256=abcd"))
        .isInstanceOf(WebhookSignatureException.class);
  }

  @Test
  void rejectsTamperedPayloadOrWrongSecret() {
    byte[] payload = "{\"action\":\"completed\"}".getBytes(StandardCharsets.UTF_8);
    String validHeader = WebhookSignatureVerifier.computeSignatureHeader(SECRET, payload);
    byte[] tampered = "{\"action\":\"requested\"}".getBytes(StandardCharsets.UTF_8);

    assertThatThrownBy(() -> verifier.verify(tampered, validHeader))
        .isInstanceOf(WebhookSignatureException.class);

    String wrongSecretHeader =
        WebhookSignatureVerifier.computeSignatureHeader("wrong-secret", payload);
    assertThatThrownBy(() -> verifier.verify(payload, wrongSecretHeader))
        .isInstanceOf(WebhookSignatureException.class);
  }

  @Test
  void failsClosedWhenWebhookSecretIsNotConfigured() {
    WebhookProperties defaults = new WebhookProperties(null, 0);
    assertThat(defaults.secret()).isEmpty();
    assertThat(defaults.maxDeliveryCacheSize()).isEqualTo(1_000);

    WebhookSignatureVerifier unconfigured = new WebhookSignatureVerifier(defaults);
    byte[] payload = "{}".getBytes(StandardCharsets.UTF_8);

    assertThatThrownBy(() -> unconfigured.verify(payload, "sha256=" + "0".repeat(64)))
        .isInstanceOf(WebhookSignatureException.class)
        .hasMessageContaining("not configured");
  }
}
