package com.autoheal.infra.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autoheal.domain.EmbeddingClient;
import com.autoheal.domain.LlmException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class GeminiEmbeddingClientTest {

  private HttpServer server;
  private String baseUrl;
  private final AtomicReference<String> requestBodySeen = new AtomicReference<>();

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress(0), 0);
    baseUrl = "http://localhost:" + server.getAddress().getPort();
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  @Test
  void returns768DimVectorFromGeminiEmbedContent() {
    String valuesJson =
        IntStream.range(0, EmbeddingClient.EMBEDDING_DIMENSION)
            .mapToObj(i -> i == 0 ? "0.5" : "0.0")
            .collect(Collectors.joining(","));
    String responseJson = "{\"embedding\":{\"values\":[" + valuesJson + "]}}";

    server.createContext(
        "/v1beta/models/gemini-embedding-001:embedContent",
        exchange -> {
          requestBodySeen.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          byte[] bytes = responseJson.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, bytes.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
          }
        });
    server.start();

    GeminiProperties props =
        new GeminiProperties(
            "test-key", baseUrl, "gemini-2.5-flash", BigDecimal.ONE, BigDecimal.ONE);
    GeminiEmbeddingClient client =
        new GeminiEmbeddingClient(RestClient.builder(), new ObjectMapper(), props);

    float[] vec = client.embed("NullPointerException in PaymentService");

    assertThat(vec).hasSize(EmbeddingClient.EMBEDDING_DIMENSION);
    assertThat(vec[0]).isEqualTo(0.5f);
    assertThat(requestBodySeen.get()).contains("\"outputDimensionality\":768");
  }

  @Test
  void throwsLlmExceptionWhenApiKeyBlank() {
    GeminiProperties props =
        new GeminiProperties("", baseUrl, "gemini-2.5-flash", BigDecimal.ONE, BigDecimal.ONE);
    GeminiEmbeddingClient client =
        new GeminiEmbeddingClient(RestClient.builder(), new ObjectMapper(), props);

    assertThatThrownBy(() -> client.embed("error"))
        .isInstanceOf(LlmException.class)
        .hasMessageContaining("GEMINI_API_KEY");
  }

  @Test
  void throwsLlmExceptionWhenDimensionMismatch() {
    String badResponse = "{\"embedding\":{\"values\":[0.1,0.2]}}";
    server.createContext(
        "/v1beta/models/gemini-embedding-001:embedContent",
        exchange -> {
          byte[] bytes = badResponse.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, bytes.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
          }
        });
    server.start();

    GeminiProperties props =
        new GeminiProperties(
            "test-key", baseUrl, "gemini-2.5-flash", BigDecimal.ONE, BigDecimal.ONE);
    GeminiEmbeddingClient client =
        new GeminiEmbeddingClient(RestClient.builder(), new ObjectMapper(), props);

    assertThatThrownBy(() -> client.embed("error"))
        .isInstanceOf(LlmException.class)
        .hasMessageContaining("768");
  }
}
