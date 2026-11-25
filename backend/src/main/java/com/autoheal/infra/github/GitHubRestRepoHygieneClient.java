package com.autoheal.infra.github;

import com.autoheal.domain.GitBranchInfo;
import com.autoheal.domain.GitHubException;
import com.autoheal.domain.GitHubRepoHygieneClient;
import com.autoheal.domain.OpenPullRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class GitHubRestRepoHygieneClient implements GitHubRepoHygieneClient {

  private static final String REFS_HEADS_PREFIX = "refs/heads/";
  private static final String AUTOHEAL_BRANCH_PREFIX = "autoheal/";

  private final RestClient restClient;
  private final ObjectMapper objectMapper;
  private final GitHubProperties properties;

  public GitHubRestRepoHygieneClient(
      RestClient.Builder restClientBuilder,
      ObjectMapper objectMapper,
      GitHubProperties properties) {
    Objects.requireNonNull(restClientBuilder, "restClientBuilder must not be null");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    this.properties = Objects.requireNonNull(properties, "properties must not be null");
    this.restClient =
        restClientBuilder
            .baseUrl(properties.apiBaseUrl())
            .defaultHeader("Accept", "application/vnd.github+json")
            .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
            .build();
  }

  @Override
  public List<OpenPullRequest> listOpenPullRequests(String owner, String repo) {
    requireToken();
    String uri = "/repos/" + owner + "/" + repo + "/pulls?state=open&per_page=100";
    JsonNode array = getJson(uri);
    List<OpenPullRequest> result = new ArrayList<>();
    for (JsonNode pr : array) {
      int number = pr.path("number").asInt(0);
      String headBranch = pr.path("head").path("ref").asText("");
      String createdAtRaw = pr.path("created_at").asText("");
      if (number > 0 && !headBranch.isBlank() && !createdAtRaw.isBlank()) {
        result.add(new OpenPullRequest(number, headBranch, Instant.parse(createdAtRaw)));
      }
    }
    return List.copyOf(result);
  }

  @Override
  public void closePullRequest(String owner, String repo, int prNumber) {
    requireToken();
    String uri = "/repos/" + owner + "/" + repo + "/pulls/" + prNumber;
    try {
      restClient
          .patch()
          .uri(uri)
          .header("Authorization", "Bearer " + properties.token())
          .contentType(MediaType.APPLICATION_JSON)
          .body(Map.of("state", "closed"))
          .retrieve()
          .toBodilessEntity();
    } catch (RestClientException e) {
      throw new GitHubException("GitHub PATCH failed for " + uri + ": " + e.getMessage(), e);
    }
  }

  @Override
  public List<GitBranchInfo> listBranchesWithPrefix(String owner, String repo, String prefix) {
    requireToken();
    String uri = "/repos/" + owner + "/" + repo + "/git/matching-refs/heads/" + prefix;
    JsonNode refs = getJson(uri);
    List<GitBranchInfo> branches = new ArrayList<>();
    for (JsonNode refNode : refs) {
      String fullRef = refNode.path("ref").asText("");
      String sha = refNode.path("object").path("sha").asText("");
      if (!fullRef.startsWith(REFS_HEADS_PREFIX) || sha.isBlank()) {
        continue;
      }
      String branchName = fullRef.substring(REFS_HEADS_PREFIX.length());
      JsonNode commit = getJson("/repos/" + owner + "/" + repo + "/git/commits/" + sha);
      String dateRaw = commit.path("committer").path("date").asText("");
      if (dateRaw.isBlank()) {
        dateRaw = commit.path("author").path("date").asText("");
      }
      if (!dateRaw.isBlank()) {
        branches.add(new GitBranchInfo(branchName, Instant.parse(dateRaw)));
      }
    }
    return List.copyOf(branches);
  }

  @Override
  public void deleteBranch(String owner, String repo, String branchName) {
    Objects.requireNonNull(branchName, "branchName must not be null");
    if (!branchName.startsWith(AUTOHEAL_BRANCH_PREFIX)) {
      throw new IllegalArgumentException(
          "Refusing to delete non-autoheal branch (must start with 'autoheal/'): " + branchName);
    }
    requireToken();
    String uri = "/repos/" + owner + "/" + repo + "/git/refs/heads/" + branchName;
    try {
      restClient
          .delete()
          .uri(uri)
          .header("Authorization", "Bearer " + properties.token())
          .retrieve()
          .toBodilessEntity();
    } catch (RestClientException e) {
      throw new GitHubException("GitHub DELETE failed for " + uri + ": " + e.getMessage(), e);
    }
  }

  private void requireToken() {
    if (properties.token() == null || properties.token().isBlank()) {
      throw new GitHubException("GITHUB_TOKEN is not configured.");
    }
  }

  private JsonNode getJson(String uri) {
    try {
      String body =
          restClient
              .get()
              .uri(uri)
              .header("Authorization", "Bearer " + properties.token())
              .retrieve()
              .body(String.class);
      if (body == null || body.isBlank()) {
        throw new GitHubException("GitHub returned empty response for " + uri);
      }
      return objectMapper.readTree(body);
    } catch (RestClientException e) {
      throw new GitHubException("GitHub GET failed for " + uri + ": " + e.getMessage(), e);
    } catch (JsonProcessingException e) {
      throw new GitHubException("Failed to parse GitHub JSON for " + uri, e);
    }
  }
}
