package com.autoheal.domain;

public interface GitHubActionsLogClient {

  /**
   * Downloads and extracts UTF-8 log text from GitHub Actions for a workflow run.
   *
   * @param owner repository owner
   * @param repo repository name
   * @param workflowRunId GitHub Actions workflow run ID
   * @return extracted log text (bounded to prevent memory exhaustion / zip bombs)
   */
  String downloadWorkflowRunLogs(String owner, String repo, long workflowRunId);
}
