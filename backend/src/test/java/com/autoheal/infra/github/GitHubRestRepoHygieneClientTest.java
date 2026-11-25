package com.autoheal.infra.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autoheal.domain.GitBranchInfo;
import com.autoheal.domain.GitHubException;
import com.autoheal.domain.OpenPullRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class GitHubRestRepoHygieneClientTest {

  private HttpServer server;
  private String baseUrl;
  private final List<String> recordedCalls = new ArrayList<>();

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/",
        exchange -> {
          String method = exchange.getRequestMethod();
          String path = exchange.getRequestURI().getPath();
          recordedCalls.add(method + " " + path);

          int status = 200;
          String respBody = "{}";
          if (method.equals("GET") && path.equals("/repos/acme/demo/pulls")) {
            respBody =
                """
                [
                  {
                    "number": 10,
                    "created_at": "2026-11-23T10:00:00Z",
                    "head": {"ref": "autoheal/fix-payment-npe-01-aaaa1111"}
                  }
                ]
                """;
          } else if (method.equals("PATCH") && path.equals("/repos/acme/demo/pulls/10")) {
            respBody = "{\"number\": 10, \"state\": \"closed\"}";
          } else if (method.equals("GET")
              && path.equals("/repos/acme/demo/git/matching-refs/heads/autoheal/")) {
            respBody =
                """
                [
                  {
                    "ref": "refs/heads/autoheal/fix-payment-npe-01-aaaa1111",
                    "object": {"sha": "commit-sha-1"}
                  }
                ]
                """;
          } else if (method.equals("GET")
              && path.equals("/repos/acme/demo/git/commits/commit-sha-1")) {
            respBody =
                """
                {
                  "sha": "commit-sha-1",
                  "committer": {"date": "2026-11-23T10:05:00Z"}
                }
                """;
          } else if (method.equals("DELETE")
              && path.equals(
                  "/repos/acme/demo/git/refs/heads/autoheal/fix-payment-npe-01-aaaa1111")) {
            status = 204;
            respBody = "";
          } else {
            status = 404;
            respBody = "{\"message\":\"not found\"}";
          }

          byte[] bytes = respBody.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(status, status == 204 ? -1 : bytes.length);
          if (status != 204) {
            try (OutputStream os = exchange.getResponseBody()) {
              os.write(bytes);
            }
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
  void listsAndClosesPullRequestsAndDeletesAutohealBranches() {
    GitHubRestRepoHygieneClient client =
        new GitHubRestRepoHygieneClient(
            RestClient.builder(), new ObjectMapper(), new GitHubProperties("ghp_test", baseUrl));

    List<OpenPullRequest> prs = client.listOpenPullRequests("acme", "demo");
    assertThat(prs)
        .containsExactly(
            new OpenPullRequest(
                10, "autoheal/fix-payment-npe-01-aaaa1111", Instant.parse("2026-11-23T10:00:00Z")));

    client.closePullRequest("acme", "demo", 10);

    List<GitBranchInfo> branches = client.listBranchesWithPrefix("acme", "demo", "autoheal/");
    assertThat(branches)
        .containsExactly(
            new GitBranchInfo(
                "autoheal/fix-payment-npe-01-aaaa1111", Instant.parse("2026-11-23T10:05:00Z")));

    client.deleteBranch("acme", "demo", "autoheal/fix-payment-npe-01-aaaa1111");

    assertThat(recordedCalls)
        .containsExactly(
            "GET /repos/acme/demo/pulls",
            "PATCH /repos/acme/demo/pulls/10",
            "GET /repos/acme/demo/git/matching-refs/heads/autoheal/",
            "GET /repos/acme/demo/git/commits/commit-sha-1",
            "DELETE /repos/acme/demo/git/refs/heads/autoheal/fix-payment-npe-01-aaaa1111");
  }

  @Test
  void refusesToDeleteNonAutohealBranchAndFailsWhenTokenMissing() {
    GitHubRestRepoHygieneClient client =
        new GitHubRestRepoHygieneClient(
            RestClient.builder(), new ObjectMapper(), new GitHubProperties("ghp_test", baseUrl));

    assertThatThrownBy(() -> client.deleteBranch("acme", "demo", "main"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("autoheal/");

    GitHubRestRepoHygieneClient noTokenClient =
        new GitHubRestRepoHygieneClient(
            RestClient.builder(), new ObjectMapper(), new GitHubProperties("", baseUrl));
    assertThatThrownBy(() -> noTokenClient.listOpenPullRequests("acme", "demo"))
        .isInstanceOf(GitHubException.class)
        .hasMessageContaining("GITHUB_TOKEN");
  }
}
