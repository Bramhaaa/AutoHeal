package com.autoheal.infra.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autoheal.domain.GitHubException;
import com.autoheal.domain.PullRequestRequest;
import com.autoheal.domain.PullRequestResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class GitHubRestPrClientTest {

  private HttpServer server;
  private final List<String> recordedCalls = new ArrayList<>();
  private final AtomicReference<String> lastAuthHeader = new AtomicReference<>();
  private final AtomicReference<String> prRequestBody = new AtomicReference<>();
  private String baseUrl;

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/",
        exchange -> {
          String method = exchange.getRequestMethod();
          String path = exchange.getRequestURI().getPath();
          recordedCalls.add(method + " " + path);
          lastAuthHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
          String reqBody =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

          String respBody;
          int status = 200;
          if (path.endsWith("/git/commits/base-sha-123")) {
            respBody = "{\"sha\": \"base-sha-123\", \"tree\": {\"sha\": \"base-tree-sha\"}}";
          } else if (path.endsWith("/git/blobs")) {
            status = 201;
            respBody = "{\"sha\": \"blob-sha-1\"}";
          } else if (path.endsWith("/git/trees")) {
            status = 201;
            respBody = "{\"sha\": \"new-tree-sha\"}";
          } else if (path.endsWith("/git/commits")) {
            status = 201;
            respBody = "{\"sha\": \"new-commit-sha\"}";
          } else if (path.endsWith("/git/refs")) {
            status = 201;
            respBody = "{\"ref\": \"refs/heads/autoheal/fix-payment-npe-01-abc123\"}";
          } else if (path.endsWith("/pulls")) {
            status = 201;
            prRequestBody.set(reqBody);
            respBody = "{\"number\": 42, \"html_url\": \"https://github.com/acme/demo/pull/42\"}";
          } else {
            status = 404;
            respBody = "{\"message\": \"not found\"}";
          }

          byte[] bytes = respBody.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(status, bytes.length);
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
  void executesGitDataApiSequenceAndOpensDraftPullRequest() {
    GitHubRestPrClient client =
        new GitHubRestPrClient(
            RestClient.builder(), new ObjectMapper(), new GitHubProperties("ghp_test", baseUrl));

    PullRequestRequest request =
        new PullRequestRequest(
            "acme",
            "demo",
            "scenario/payment-npe-01",
            "autoheal/fix-payment-npe-01-abc123",
            "base-sha-123",
            "fix(payment): guard null discount",
            "## Root Cause Analysis\nNull check added.",
            Map.of("src/main/java/com/demo/PaymentService.java", "class PaymentService {}"));

    PullRequestResult result = client.createDraftPullRequest(request);

    assertThat(result.number()).isEqualTo(42);
    assertThat(result.htmlUrl()).isEqualTo("https://github.com/acme/demo/pull/42");
    assertThat(result.branchName()).isEqualTo("autoheal/fix-payment-npe-01-abc123");
    assertThat(lastAuthHeader.get()).isEqualTo("Bearer ghp_test");
    assertThat(prRequestBody.get()).contains("\"draft\":true");
    assertThat(recordedCalls)
        .containsExactly(
            "GET /repos/acme/demo/git/commits/base-sha-123",
            "POST /repos/acme/demo/git/blobs",
            "POST /repos/acme/demo/git/trees",
            "POST /repos/acme/demo/git/commits",
            "POST /repos/acme/demo/git/refs",
            "POST /repos/acme/demo/pulls");
  }

  @Test
  void failsLoudlyWhenTokenMissingOrApiErrors() {
    GitHubRestPrClient noTokenClient =
        new GitHubRestPrClient(
            RestClient.builder(), new ObjectMapper(), new GitHubProperties("", baseUrl));
    PullRequestRequest request =
        new PullRequestRequest(
            "acme",
            "demo",
            "main",
            "autoheal/fix-1",
            "missing-sha",
            "title",
            "body",
            Map.of("src/main/A.java", "class A {}"));

    assertThatThrownBy(() -> noTokenClient.createDraftPullRequest(request))
        .isInstanceOf(GitHubException.class)
        .hasMessageContaining("GITHUB_TOKEN");

    GitHubRestPrClient client =
        new GitHubRestPrClient(
            RestClient.builder(), new ObjectMapper(), new GitHubProperties("ghp_test", baseUrl));
    assertThatThrownBy(() -> client.createDraftPullRequest(request))
        .isInstanceOf(GitHubException.class);
  }
}
