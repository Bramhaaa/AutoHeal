package com.autoheal.infra.github;

import com.autoheal.domain.GitHubException;
import com.autoheal.domain.GitHubPrClient;
import com.autoheal.domain.PullRequestRequest;
import com.autoheal.domain.PullRequestResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class GitHubRestPrClient implements GitHubPrClient {

  private final RestClient restClient;
  private final ObjectMapper objectMapper;
  private final GitHubProperties properties;

  public GitHubRestPrClient(
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
  public PullRequestResult createDraftPullRequest(PullRequestRequest request) {
    Objects.requireNonNull(request, "request must not be null");
    if (properties.token() == null || properties.token().isBlank()) {
      throw new GitHubException("GITHUB_TOKEN is not configured.");
    }

    String repoBase = "/repos/" + request.owner() + "/" + request.repo();

    // 1. Resolve base tree SHA from base commit
    JsonNode baseCommit = getJson(repoBase + "/git/commits/" + request.baseCommitSha());
    String baseTreeSha = requireText(baseCommit.path("tree"), "sha", "base commit tree");

    // 2. Create blobs for each modified/created file
    List<Map<String, Object>> treeEntries = new ArrayList<>();
    for (Map.Entry<String, String> file : request.fileContents().entrySet()) {
      JsonNode blob =
          postJson(
              repoBase + "/git/blobs", Map.of("content", file.getValue(), "encoding", "utf-8"));
      String blobSha = requireText(blob, "sha", "blob");
      treeEntries.add(
          Map.of("path", file.getKey(), "mode", "100644", "type", "blob", "sha", blobSha));
    }

    // 3. Create new tree
    JsonNode newTree =
        postJson(repoBase + "/git/trees", Map.of("base_tree", baseTreeSha, "tree", treeEntries));
    String newTreeSha = requireText(newTree, "sha", "new tree");

    // 4. Create commit
    JsonNode newCommit =
        postJson(
            repoBase + "/git/commits",
            Map.of(
                "message",
                request.title(),
                "tree",
                newTreeSha,
                "parents",
                List.of(request.baseCommitSha())));
    String newCommitSha = requireText(newCommit, "sha", "new commit");

    // 5. Create branch ref
    postJson(
        repoBase + "/git/refs",
        Map.of("ref", "refs/heads/" + request.headBranch(), "sha", newCommitSha));

    // 6. Open Draft PR
    JsonNode pr =
        postJson(
            repoBase + "/pulls",
            Map.of(
                "title",
                request.title(),
                "head",
                request.headBranch(),
                "base",
                request.baseBranch(),
                "body",
                request.body(),
                "draft",
                true));
    int number = pr.path("number").asInt(0);
    String htmlUrl = requireText(pr, "html_url", "pull request");
    return new PullRequestResult(number, htmlUrl, request.headBranch());
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
      return parseJson(body, uri);
    } catch (RestClientException e) {
      throw new GitHubException("GitHub GET failed for " + uri + ": " + e.getMessage(), e);
    }
  }

  private JsonNode postJson(String uri, Map<String, Object> payload) {
    try {
      String body =
          restClient
              .post()
              .uri(uri)
              .header("Authorization", "Bearer " + properties.token())
              .contentType(MediaType.APPLICATION_JSON)
              .body(payload)
              .retrieve()
              .body(String.class);
      return parseJson(body, uri);
    } catch (RestClientException e) {
      throw new GitHubException("GitHub POST failed for " + uri + ": " + e.getMessage(), e);
    }
  }

  private JsonNode parseJson(String body, String uri) {
    if (body == null || body.isBlank()) {
      throw new GitHubException("GitHub returned empty response for " + uri);
    }
    try {
      return objectMapper.readTree(body);
    } catch (JsonProcessingException e) {
      throw new GitHubException("Failed to parse GitHub JSON for " + uri, e);
    }
  }

  private String requireText(JsonNode node, String field, String context) {
    String value = node.path(field).asText("");
    if (value.isBlank()) {
      throw new GitHubException("Missing '" + field + "' in GitHub " + context + " response");
    }
    return value;
  }
}
