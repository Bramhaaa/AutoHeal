package com.autoheal.infra.llm;

import com.autoheal.domain.EmbeddingClient;
import com.autoheal.domain.LlmException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class GeminiEmbeddingClient implements EmbeddingClient {

  private static final String EMBEDDING_MODEL = "gemini-embedding-001";

  private final RestClient restClient;
  private final ObjectMapper objectMapper;
  private final GeminiProperties properties;

  public GeminiEmbeddingClient(
      RestClient.Builder restClientBuilder,
      ObjectMapper objectMapper,
      GeminiProperties properties) {
    Objects.requireNonNull(restClientBuilder, "restClientBuilder must not be null");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    this.properties = Objects.requireNonNull(properties, "properties must not be null");
    this.restClient = restClientBuilder.baseUrl(properties.baseUrl()).build();
  }

  @Override
  public float[] embed(String text) {
    Objects.requireNonNull(text, "text must not be null");
    if (properties.apiKey() == null || properties.apiKey().isBlank()) {
      throw new LlmException("GEMINI_API_KEY is not configured for embeddings.");
    }

    Map<String, Object> body =
        Map.of(
            "model",
            "models/" + EMBEDDING_MODEL,
            "content",
            Map.of("parts", List.of(Map.of("text", text))),
            "outputDimensionality",
            EMBEDDING_DIMENSION);

    String rawResponse;
    try {
      rawResponse =
          restClient
              .post()
              .uri("/v1beta/models/{model}:embedContent", EMBEDDING_MODEL)
              .header("x-goog-api-key", properties.apiKey())
              .contentType(MediaType.APPLICATION_JSON)
              .body(body)
              .retrieve()
              .body(String.class);
    } catch (RestClientException e) {
      throw new LlmException("Gemini embedding request failed: " + e.getMessage(), e);
    }

    return parseEmbedding(rawResponse);
  }

  private float[] parseEmbedding(String rawResponse) {
    if (rawResponse == null || rawResponse.isBlank()) {
      throw new LlmException("Gemini embedding returned empty response");
    }
    try {
      JsonNode root = objectMapper.readTree(rawResponse);
      JsonNode values = root.path("embedding").path("values");
      if (!values.isArray() || values.size() != EMBEDDING_DIMENSION) {
        throw new LlmException(
            "Expected " + EMBEDDING_DIMENSION + "-dim embedding vector, got " + values.size());
      }
      float[] vector = new float[EMBEDDING_DIMENSION];
      for (int i = 0; i < EMBEDDING_DIMENSION; i++) {
        vector[i] = (float) values.get(i).asDouble();
      }
      return vector;
    } catch (JsonProcessingException e) {
      throw new LlmException("Failed to parse Gemini embedding response", e);
    }
  }
}
