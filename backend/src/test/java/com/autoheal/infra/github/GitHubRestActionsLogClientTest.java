package com.autoheal.infra.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autoheal.domain.GitHubException;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class GitHubRestActionsLogClientTest {

  private HttpServer server;
  private String baseUrl;
  private final AtomicReference<String> authHeaderSeen = new AtomicReference<>();

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
  void downloadsAndExtractsZipLogEntries() throws IOException {
    byte[] zipBytes =
        buildZip(
            "1_build.txt",
            "[INFO] Running PaymentServiceTest\n",
            "2_test.txt",
            "[ERROR] BUILD FAILURE - NullPointerException\n");

    server.createContext(
        "/repos/acme/demo/actions/runs/9912/logs",
        exchange -> {
          authHeaderSeen.set(exchange.getRequestHeaders().getFirst("Authorization"));
          exchange.getResponseHeaders().add("Content-Type", "application/zip");
          exchange.sendResponseHeaders(200, zipBytes.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(zipBytes);
          }
        });
    server.start();

    GitHubRestActionsLogClient client =
        new GitHubRestActionsLogClient(
            RestClient.builder(), new GitHubProperties("ghp_test", baseUrl));

    String logs = client.downloadWorkflowRunLogs("acme", "demo", 9912L);

    assertThat(authHeaderSeen.get()).isEqualTo("Bearer ghp_test");
    assertThat(logs)
        .contains("Running PaymentServiceTest")
        .contains("BUILD FAILURE - NullPointerException");
  }

  @Test
  void capsExtractedBytesToPreventZipBomb() throws IOException {
    String hugeLine = "X".repeat(1024) + "\n";
    StringBuilder hugeContent = new StringBuilder();
    for (int i = 0; i < 700; i++) {
      hugeContent.append(hugeLine);
    }
    byte[] zipBytes = buildZip("huge.txt", hugeContent.toString());

    server.createContext(
        "/repos/acme/demo/actions/runs/42/logs",
        exchange -> {
          exchange.sendResponseHeaders(200, zipBytes.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(zipBytes);
          }
        });
    server.start();

    GitHubRestActionsLogClient client =
        new GitHubRestActionsLogClient(
            RestClient.builder(), new GitHubProperties("ghp_test", baseUrl));

    String logs = client.downloadWorkflowRunLogs("acme", "demo", 42L);
    assertThat(logs.getBytes(StandardCharsets.UTF_8).length)
        .isLessThanOrEqualTo(GitHubRestActionsLogClient.MAX_EXTRACTED_BYTES + 256);
  }

  @Test
  void throwsWhenTokenMissing() {
    GitHubRestActionsLogClient client =
        new GitHubRestActionsLogClient(RestClient.builder(), new GitHubProperties("", baseUrl));

    assertThatThrownBy(() -> client.downloadWorkflowRunLogs("acme", "demo", 1L))
        .isInstanceOf(GitHubException.class)
        .hasMessageContaining("GITHUB_TOKEN");
  }

  @Test
  void throwsOnHttpError() {
    server.createContext(
        "/repos/acme/demo/actions/runs/404/logs",
        exchange -> {
          exchange.sendResponseHeaders(404, -1);
          exchange.close();
        });
    server.start();

    GitHubRestActionsLogClient client =
        new GitHubRestActionsLogClient(
            RestClient.builder(), new GitHubProperties("ghp_test", baseUrl));

    assertThatThrownBy(() -> client.downloadWorkflowRunLogs("acme", "demo", 404L))
        .isInstanceOf(GitHubException.class);
  }

  private static byte[] buildZip(String... nameAndContent) throws IOException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    try (ZipOutputStream zos = new ZipOutputStream(baos)) {
      for (int i = 0; i < nameAndContent.length; i += 2) {
        zos.putNextEntry(new ZipEntry(nameAndContent[i]));
        zos.write(nameAndContent[i + 1].getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
      }
    }
    return baos.toByteArray();
  }
}
