package com.autoheal.domain;

import java.util.List;

public interface GitHubRepoHygieneClient {

  List<OpenPullRequest> listOpenPullRequests(String owner, String repo);

  void closePullRequest(String owner, String repo, int prNumber);

  List<GitBranchInfo> listBranchesWithPrefix(String owner, String repo, String prefix);

  void deleteBranch(String owner, String repo, String branchName);
}
