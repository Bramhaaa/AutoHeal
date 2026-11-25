package com.autoheal.service;

import com.autoheal.domain.GitBranchInfo;
import com.autoheal.domain.GitHubRepoHygieneClient;
import com.autoheal.domain.OpenPullRequest;
import com.autoheal.service.DraftPullRequestService.OwnerRepo;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class DemoRepoHygieneService {

  private static final Logger log = LoggerFactory.getLogger(DemoRepoHygieneService.class);
  static final String AUTOHEAL_BRANCH_PREFIX = "autoheal/";
  static final Duration STALE_AGE = Duration.ofHours(24);

  private final GitHubRepoHygieneClient hygieneClient;
  private final PipelineProperties properties;
  private final Clock clock;

  @Autowired
  public DemoRepoHygieneService(
      GitHubRepoHygieneClient hygieneClient, PipelineProperties properties) {
    this(hygieneClient, properties, Clock.systemUTC());
  }

  DemoRepoHygieneService(
      GitHubRepoHygieneClient hygieneClient, PipelineProperties properties, Clock clock) {
    this.hygieneClient = Objects.requireNonNull(hygieneClient, "hygieneClient");
    this.properties = Objects.requireNonNull(properties, "properties");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Scheduled(cron = "${autoheal.hygiene.cron:0 0 3 * * *}")
  public HygieneSummary cleanStaleDemoArtifacts() {
    if (!properties.prCreationEnabled()) {
      return new HygieneSummary(0, 0);
    }

    int prsClosed = 0;
    int branchesDeleted = 0;
    try {
      OwnerRepo ownerRepo =
          DraftPullRequestService.resolveOwnerRepo(
              properties.demoRepoName(), properties.demoRepoUri());
      Instant cutoff = clock.instant().minus(STALE_AGE);

      for (OpenPullRequest pr :
          hygieneClient.listOpenPullRequests(ownerRepo.owner(), ownerRepo.repo())) {
        if (pr.headBranch().startsWith(AUTOHEAL_BRANCH_PREFIX) && pr.createdAt().isBefore(cutoff)) {
          hygieneClient.closePullRequest(ownerRepo.owner(), ownerRepo.repo(), pr.number());
          prsClosed++;
        }
      }

      for (GitBranchInfo branch :
          hygieneClient.listBranchesWithPrefix(
              ownerRepo.owner(), ownerRepo.repo(), AUTOHEAL_BRANCH_PREFIX)) {
        if (branch.name().startsWith(AUTOHEAL_BRANCH_PREFIX)
            && branch.committedAt().isBefore(cutoff)) {
          hygieneClient.deleteBranch(ownerRepo.owner(), ownerRepo.repo(), branch.name());
          branchesDeleted++;
        }
      }

      if (prsClosed > 0 || branchesDeleted > 0) {
        log.info(
            "Demo repo hygiene closed {} stale PR(s) and deleted {} stale autoheal/* branch(es)",
            prsClosed,
            branchesDeleted);
      }
    } catch (RuntimeException e) {
      // Scheduled hygiene boundary: log GitHub errors without killing the Spring scheduler.
      log.warn("Demo repo hygiene run failed: {}", e.getMessage());
    }
    return new HygieneSummary(prsClosed, branchesDeleted);
  }

  public record HygieneSummary(int prsClosed, int branchesDeleted) {}
}
