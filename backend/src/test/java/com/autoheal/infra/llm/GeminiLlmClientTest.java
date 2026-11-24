package com.autoheal.infra.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autoheal.domain.LlmException;
import com.autoheal.domain.LlmMessage;
import com.autoheal.domain.LlmMessage.Role;
import com.autoheal.domain.LlmToolCall;
import com.autoheal.domain.LlmTurnResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class GeminiLlmClientTest {

  private HttpServer server;
  private final AtomicReference<String> lastPath = new AtomicReference<>();
  private final AtomicReference<String> lastApiKeyHeader = new AtomicReference<>();
  private final AtomicReference<String> lastRequestBody = new AtomicReference<>();
  private final AtomicReference<String> nextResponseBody = new AtomicReference<>("{}");
  private final AtomicReference<Integer> nextStatusCode = new AtomicReference<>(200);
  private String baseUrl;

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/",
        exchange -> {
          lastPath.set(exchange.getRequestURI().toString());
          lastApiKeyHeader.set(exchange.getRequestHeaders().getFirst("x-goog-api-key"));
          lastRequestBody.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          byte[] bytes = nextResponseBody.get().getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(nextStatusCode.get(), bytes.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
          }
        });
    server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  @Test
  void sendsApiKeyInHeaderParsesProposePatchAndComputesTokenCost() {
    nextResponseBody.set(
        """
        {
          "candidates": [
            {
              "content": {
                "parts": [
                  {
                    "functionCall": {
                      "name": "propose_patch",
                      "args": {
                        "root_cause_summary": "Null discount dereference",
                        "edits": [
                          {
                            "file": "src/main/java/com/demo/PaymentService.java",
                            "search": "return 100 - discount;",
                            "replace": "return 100 - (discount == null ? 0 : discount);"
                          }
                        ],
                        "new_test_files": [
                          {
                            "path": "src/test/java/com/demo/NullDiscountTest.java",
                            "content": "class NullDiscountTest {}"
                          }
                        ]
                      }
                    }
                  }
                ]
              }
            }
          ],
          "usageMetadata": {
            "promptTokenCount": 1000,
            "candidatesTokenCount": 500
          }
        }
        """);

    GeminiLlmClient client = createClient("test-secret-key");
    LlmTurnResponse response =
        client.nextAction(
            List.of(
                new LlmMessage(Role.SYSTEM, "You are AutoHeal."),
                new LlmMessage(Role.USER, "Fix NPE in PaymentService")));

    assertThat(lastPath.get()).isEqualTo("/v1beta/models/gemini-2.5-flash:generateContent");
    assertThat(lastPath.get()).doesNotContain("test-secret-key");
    assertThat(lastApiKeyHeader.get()).isEqualTo("test-secret-key");
    assertThat(lastRequestBody.get())
        .contains("system_instruction")
        .contains("propose_patch")
        .contains("list_files")
        .contains("read_file")
        .contains("search_code");

    assertThat(response.toolCall()).isInstanceOf(LlmToolCall.ProposePatchCall.class);
    LlmToolCall.ProposePatchCall patchCall = (LlmToolCall.ProposePatchCall) response.toolCall();
    assertThat(patchCall.proposal().rootCauseSummary()).isEqualTo("Null discount dereference");
    assertThat(patchCall.proposal().edits()).hasSize(1);
    assertThat(patchCall.proposal().newTestFiles()).hasSize(1);

    assertThat(response.usage().model()).isEqualTo("gemini-2.5-flash");
    assertThat(response.usage().promptTokens()).isEqualTo(1000);
    assertThat(response.usage().completionTokens()).isEqualTo(500);
    // 1000 * 0.15 / 1_000_000 + 500 * 0.60 / 1_000_000 = 0.000150 + 0.000300 = 0.000450
    assertThat(response.usage().estimatedCostUsd()).isEqualByComparingTo("0.000450");
  }

  @Test
  void parsesReadOnlyToolCalls() {
    GeminiLlmClient client = createClient("test-key");

    nextResponseBody.set(
        """
        {
          "candidates": [{"content": {"parts": [{"functionCall": {"name": "read_file", "args": {"path": "src/main/A.java", "start_line": 1, "end_line": 50}}}]}}],
          "usageMetadata": {"promptTokenCount": 10, "candidatesTokenCount": 5}
        }
        """);
    LlmTurnResponse readResp = client.nextAction(List.of(new LlmMessage(Role.USER, "hi")));
    assertThat(readResp.toolCall())
        .isEqualTo(new LlmToolCall.ReadFileCall("src/main/A.java", 1, 50));

    nextResponseBody.set(
        """
        {
          "candidates": [{"content": {"parts": [{"functionCall": {"name": "list_files", "args": {"dir": "src/main", "glob": "*.java"}}}]}}],
          "usageMetadata": {"promptTokenCount": 10, "candidatesTokenCount": 5}
        }
        """);
    LlmTurnResponse listResp = client.nextAction(List.of(new LlmMessage(Role.USER, "hi")));
    assertThat(listResp.toolCall()).isEqualTo(new LlmToolCall.ListFilesCall("src/main", "*.java"));

    nextResponseBody.set(
        """
        {
          "candidates": [{"content": {"parts": [{"functionCall": {"name": "search_code", "args": {"query": "discount", "glob": "*.java"}}}]}}],
          "usageMetadata": {"promptTokenCount": 10, "candidatesTokenCount": 5}
        }
        """);
    LlmTurnResponse searchResp = client.nextAction(List.of(new LlmMessage(Role.USER, "hi")));
    assertThat(searchResp.toolCall())
        .isEqualTo(new LlmToolCall.SearchCodeCall("discount", "*.java"));
  }

  @Test
  void failsLoudlyWhenApiKeyMissingOrHttpErrorOrMissingFunctionCall() {
    GeminiLlmClient noKeyClient = createClient("");
    assertThatThrownBy(() -> noKeyClient.nextAction(List.of(new LlmMessage(Role.USER, "hi"))))
        .isInstanceOf(LlmException.class)
        .hasMessageContaining("GEMINI_API_KEY");

    GeminiLlmClient client = createClient("test-key");
    nextStatusCode.set(429);
    nextResponseBody.set("{\"error\": \"rate limited\"}");
    assertThatThrownBy(() -> client.nextAction(List.of(new LlmMessage(Role.USER, "hi"))))
        .isInstanceOf(LlmException.class);

    nextStatusCode.set(200);
    nextResponseBody.set(
        "{\"candidates\": [{\"content\": {\"parts\": [{\"text\": \"no tool\"}]}}]}");
    assertThatThrownBy(() -> client.nextAction(List.of(new LlmMessage(Role.USER, "hi"))))
        .isInstanceOf(LlmException.class)
        .hasMessageContaining("functionCall");
  }

  private GeminiLlmClient createClient(String apiKey) {
    GeminiProperties props =
        new GeminiProperties(
            apiKey, baseUrl, "gemini-2.5-flash", new BigDecimal("0.15"), new BigDecimal("0.60"));
    return new GeminiLlmClient(RestClient.builder(), new ObjectMapper(), props);
  }
}
