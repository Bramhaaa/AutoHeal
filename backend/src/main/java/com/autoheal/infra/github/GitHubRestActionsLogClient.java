package com.autoheal.infra.github;

import com.autoheal.domain.GitHubActionsLogClient;
import com.autoheal.domain.GitHubException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class GitHubRestActionsLogClient implements GitHubActionsLogClient {

  static final int MAX_EXTRACTED_BYTES = 512 * 1024;
  private static final int BUFFER_SIZE = 4096;

  private final RestClient restClient;
  private final GitHubProperties properties;

  public GitHubRestActionsLogClient(
      RestClient.Builder restClientBuilder, GitHubProperties properties) {
    Objects.requireNonNull(restClientBuilder, "restClientBuilder must not be null");
    this.properties = Objects.requireNonNull(properties, "properties must not be null");
    this.restClient =
        restClientBuilder
            .baseUrl(properties.apiBaseUrl())
            .defaultHeader("Accept", "application/vnd.github+json")
            .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
            .build();
  }

  @Override
  public String downloadWorkflowRunLogs(String owner, String repo, long workflowRunId) {
    Objects.requireNonNull(owner, "owner must not be null");
    Objects.requireNonNull(repo, "repo must not be null");
    if (properties.token() == null || properties.token().isBlank()) {
      throw new GitHubException("GITHUB_TOKEN is not configured.");
    }

    String uri = "/repos/" + owner + "/" + repo + "/actions/runs/" + workflowRunId + "/logs";
    byte[] zipBytes;
    try {
      zipBytes =
          restClient
              .get()
              .uri(uri)
              .header("Authorization", "Bearer " + properties.token())
              .retrieve()
              .body(byte[].class);
    } catch (RestClientException e) {
      throw new GitHubException(
          "Failed to download GitHub Actions logs for " + uri + ": " + e.getMessage(), e);
    }

    if (zipBytes == null || zipBytes.length == 0) {
      throw new GitHubException("GitHub returned empty log archive for " + uri);
    }
    return extractBoundedLogs(zipBytes);
  }

  private static String extractBoundedLogs(byte[] zipBytes) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    int totalWritten = 0;
    byte[] buffer = new byte[BUFFER_SIZE];

    try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
      ZipEntry entry;
      while ((entry = zis.getNextEntry()) != null && totalWritten < MAX_EXTRACTED_BYTES) {
        if (entry.isDirectory()) {
          zis.closeEntry();
          continue;
        }
        int read;
        while ((read = zis.read(buffer)) != -1 && totalWritten < MAX_EXTRACTED_BYTES) {
          int toWrite = Math.min(read, MAX_EXTRACTED_BYTES - totalWritten);
          out.write(buffer, 0, toWrite);
          totalWritten += toWrite;
        }
        if (totalWritten < MAX_EXTRACTED_BYTES) {
          out.write('\n');
          totalWritten++;
        }
        zis.closeEntry();
      }
    } catch (IOException e) {
      throw new GitHubException("Failed to extract GitHub Actions log zip", e);
    }

    return out.toString(StandardCharsets.UTF_8);
  }
}
