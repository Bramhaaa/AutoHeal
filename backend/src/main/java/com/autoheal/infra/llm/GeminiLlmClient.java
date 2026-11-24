package com.autoheal.infra.llm;

import com.autoheal.domain.LlmClient;
import com.autoheal.domain.LlmException;
import com.autoheal.domain.LlmMessage;
import com.autoheal.domain.LlmMessage.Role;
import com.autoheal.domain.LlmToolCall;
import com.autoheal.domain.LlmTurnResponse;
import com.autoheal.domain.LlmUsage;
import com.autoheal.domain.NewTestFile;
import com.autoheal.domain.PatchProposal;
import com.autoheal.domain.SearchReplaceEdit;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class GeminiLlmClient implements LlmClient {

  private static final BigDecimal ONE_MILLION = new BigDecimal("1000000");
  private static final int COST_SCALE = 6;

  private final RestClient restClient;
  private final ObjectMapper objectMapper;
  private final GeminiProperties properties;

  public GeminiLlmClient(
      RestClient.Builder restClientBuilder,
      ObjectMapper objectMapper,
      GeminiProperties properties) {
    Objects.requireNonNull(restClientBuilder, "restClientBuilder must not be null");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    this.properties = Objects.requireNonNull(properties, "properties must not be null");
    this.restClient = restClientBuilder.baseUrl(properties.baseUrl()).build();
  }

  @Override
  public LlmTurnResponse nextAction(List<LlmMessage> conversation) {
    Objects.requireNonNull(conversation, "conversation must not be null");
    if (properties.apiKey() == null || properties.apiKey().isBlank()) {
      throw new LlmException("GEMINI_API_KEY is not configured.");
    }

    Map<String, Object> requestBody = buildRequestBody(conversation);
    long startNanos = System.nanoTime();
    String responseJson;
    try {
      responseJson =
          restClient
              .post()
              .uri("/v1beta/models/{model}:generateContent", properties.model())
              .header("x-goog-api-key", properties.apiKey())
              .contentType(MediaType.APPLICATION_JSON)
              .body(requestBody)
              .retrieve()
              .body(String.class);
    } catch (RestClientException e) {
      throw new LlmException("Gemini API request failed: " + e.getMessage(), e);
    }
    long latencyMs = (System.nanoTime() - startNanos) / 1_000_000L;

    if (responseJson == null || responseJson.isBlank()) {
      throw new LlmException("Gemini API returned an empty response body.");
    }
    return parseResponse(responseJson, latencyMs);
  }

  private Map<String, Object> buildRequestBody(List<LlmMessage> conversation) {
    Map<String, Object> body = new LinkedHashMap<>();
    List<String> systemTexts = new ArrayList<>();
    List<Map<String, Object>> contents = new ArrayList<>();

    for (LlmMessage msg : conversation) {
      if (msg.role() == Role.SYSTEM) {
        systemTexts.add(msg.content());
      } else {
        String text = msg.role() == Role.TOOL ? "[TOOL OUTPUT]\n" + msg.content() : msg.content();
        contents.add(Map.of("role", "user", "parts", List.of(Map.of("text", text))));
      }
    }

    if (!systemTexts.isEmpty()) {
      body.put(
          "system_instruction",
          Map.of("parts", List.of(Map.of("text", String.join("\n", systemTexts)))));
    }
    body.put("contents", contents);
    body.put("tools", List.of(Map.of("function_declarations", toolDeclarations())));
    body.put("tool_config", Map.of("function_calling_config", Map.of("mode", "ANY")));
    return body;
  }

  private List<Map<String, Object>> toolDeclarations() {
    return List.of(
        Map.of(
            "name",
            "list_files",
            "description",
            "List files in the local workspace.",
            "parameters",
            Map.of(
                "type",
                "OBJECT",
                "properties",
                Map.of(
                    "dir", Map.of("type", "STRING"),
                    "glob", Map.of("type", "STRING")))),
        Map.of(
            "name",
            "read_file",
            "description",
            "Read a line range from a file (max 400 lines).",
            "parameters",
            Map.of(
                "type",
                "OBJECT",
                "properties",
                Map.of(
                    "path", Map.of("type", "STRING"),
                    "start_line", Map.of("type", "INTEGER"),
                    "end_line", Map.of("type", "INTEGER")),
                "required",
                List.of("path", "start_line", "end_line"))),
        Map.of(
            "name",
            "search_code",
            "description",
            "Search literal text in workspace files.",
            "parameters",
            Map.of(
                "type",
                "OBJECT",
                "properties",
                Map.of(
                    "query", Map.of("type", "STRING"),
                    "glob", Map.of("type", "STRING")),
                "required",
                List.of("query"))),
        Map.of(
            "name",
            "propose_patch",
            "description",
            "Submit exact search/replace edits under src/main/ and optional new tests under src/test/.",
            "parameters",
            Map.of(
                "type",
                "OBJECT",
                "properties",
                Map.of(
                    "root_cause_summary",
                    Map.of("type", "STRING"),
                    "edits",
                    Map.of(
                        "type",
                        "ARRAY",
                        "items",
                        Map.of(
                            "type",
                            "OBJECT",
                            "properties",
                            Map.of(
                                "file", Map.of("type", "STRING"),
                                "search", Map.of("type", "STRING"),
                                "replace", Map.of("type", "STRING")),
                            "required",
                            List.of("file", "search", "replace"))),
                    "new_test_files",
                    Map.of(
                        "type",
                        "ARRAY",
                        "items",
                        Map.of(
                            "type",
                            "OBJECT",
                            "properties",
                            Map.of(
                                "path", Map.of("type", "STRING"),
                                "content", Map.of("type", "STRING")),
                            "required",
                            List.of("path", "content")))),
                "required",
                List.of("root_cause_summary", "edits"))));
  }

  private LlmTurnResponse parseResponse(String responseJson, long latencyMs) {
    JsonNode root;
    try {
      root = objectMapper.readTree(responseJson);
    } catch (JsonProcessingException e) {
      throw new LlmException("Failed to parse Gemini JSON response", e);
    }

    JsonNode functionCall = findFunctionCall(root);
    if (functionCall == null) {
      throw new LlmException("Gemini response did not contain a functionCall part.");
    }

    String name = functionCall.path("name").asText("");
    JsonNode args = functionCall.path("args");
    LlmToolCall toolCall = mapToolCall(name, args);

    JsonNode usageNode = root.path("usageMetadata");
    int promptTokens = usageNode.path("promptTokenCount").asInt(0);
    int completionTokens = usageNode.path("candidatesTokenCount").asInt(0);
    BigDecimal cost = computeCost(promptTokens, completionTokens);
    LlmUsage usage =
        new LlmUsage(properties.model(), promptTokens, completionTokens, cost, latencyMs);

    return new LlmTurnResponse(toolCall, usage);
  }

  private JsonNode findFunctionCall(JsonNode root) {
    JsonNode parts = root.path("candidates").path(0).path("content").path("parts");
    if (!parts.isArray()) {
      return null;
    }
    for (JsonNode part : parts) {
      if (part.has("functionCall")) {
        return part.get("functionCall");
      }
    }
    return null;
  }

  private LlmToolCall mapToolCall(String name, JsonNode args) {
    return switch (name) {
      case "list_files" ->
          new LlmToolCall.ListFilesCall(args.path("dir").asText(""), args.path("glob").asText(""));
      case "read_file" ->
          new LlmToolCall.ReadFileCall(
              args.path("path").asText(""),
              args.path("start_line").asInt(1),
              args.path("end_line").asInt(200));
      case "search_code" ->
          new LlmToolCall.SearchCodeCall(
              args.path("query").asText(""), args.path("glob").asText(""));
      case "propose_patch" -> new LlmToolCall.ProposePatchCall(parseProposal(args));
      default -> throw new LlmException("Unknown functionCall returned by Gemini: " + name);
    };
  }

  private PatchProposal parseProposal(JsonNode args) {
    List<SearchReplaceEdit> edits = new ArrayList<>();
    JsonNode editsNode = args.path("edits");
    if (editsNode.isArray()) {
      for (JsonNode e : editsNode) {
        edits.add(
            new SearchReplaceEdit(
                e.path("file").asText(""),
                e.path("search").asText(""),
                e.path("replace").asText("")));
      }
    }

    List<NewTestFile> newTests = new ArrayList<>();
    JsonNode testsNode = args.path("new_test_files");
    if (testsNode.isArray()) {
      for (JsonNode t : testsNode) {
        newTests.add(new NewTestFile(t.path("path").asText(""), t.path("content").asText("")));
      }
    }

    return new PatchProposal(edits, newTests, args.path("root_cause_summary").asText(""));
  }

  private BigDecimal computeCost(int promptTokens, int completionTokens) {
    BigDecimal promptCost =
        BigDecimal.valueOf(promptTokens)
            .multiply(properties.promptPricePerMillion())
            .divide(ONE_MILLION, COST_SCALE, RoundingMode.HALF_UP);
    BigDecimal completionCost =
        BigDecimal.valueOf(completionTokens)
            .multiply(properties.completionPricePerMillion())
            .divide(ONE_MILLION, COST_SCALE, RoundingMode.HALF_UP);
    return promptCost.add(completionCost);
  }
}
