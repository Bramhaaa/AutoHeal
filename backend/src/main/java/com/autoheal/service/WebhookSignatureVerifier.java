package com.autoheal.service;

import com.autoheal.domain.WebhookSignatureException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;

@Service
public class WebhookSignatureVerifier {

  private static final String HMAC_ALGORITHM = "HmacSHA256";
  private static final String SIGNATURE_PREFIX = "sha256=";
  private static final int SHA256_BYTES = 32;

  private final WebhookProperties properties;

  public WebhookSignatureVerifier(WebhookProperties properties) {
    this.properties = Objects.requireNonNull(properties, "properties must not be null");
  }

  public void verify(byte[] payload, String signatureHeader) {
    Objects.requireNonNull(payload, "payload must not be null");
    String secret = properties.secret();
    if (secret == null || secret.isBlank()) {
      throw new WebhookSignatureException("GITHUB_WEBHOOK_SECRET is not configured");
    }
    if (signatureHeader == null || !signatureHeader.startsWith(SIGNATURE_PREFIX)) {
      throw new WebhookSignatureException("Missing or malformed X-Hub-Signature-256 header");
    }

    String providedHex = signatureHeader.substring(SIGNATURE_PREFIX.length()).trim();
    byte[] providedBytes;
    try {
      providedBytes = HexFormat.of().parseHex(providedHex);
    } catch (IllegalArgumentException e) {
      throw new WebhookSignatureException("Invalid hex digest in X-Hub-Signature-256 header");
    }
    if (providedBytes.length != SHA256_BYTES) {
      throw new WebhookSignatureException("Invalid digest length in X-Hub-Signature-256 header");
    }

    byte[] expectedBytes = computeHmacSha256(secret, payload);
    if (!MessageDigest.isEqual(expectedBytes, providedBytes)) {
      throw new WebhookSignatureException("Webhook HMAC-SHA256 signature verification failed");
    }
  }

  public static String computeSignatureHeader(String secret, byte[] payload) {
    return SIGNATURE_PREFIX + HexFormat.of().formatHex(computeHmacSha256(secret, payload));
  }

  private static byte[] computeHmacSha256(String secret, byte[] payload) {
    try {
      Mac mac = Mac.getInstance(HMAC_ALGORITHM);
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
      return mac.doFinal(payload);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Failed to compute HMAC-SHA256", e);
    }
  }
}
