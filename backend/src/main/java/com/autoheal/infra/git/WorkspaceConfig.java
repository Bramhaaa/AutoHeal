package com.autoheal.infra.git;

import com.autoheal.domain.WorkspaceManager;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WorkspaceConfig {

  @Bean
  public WorkspaceManager workspaceManager(
      @Value("${autoheal.workspace.root:${java.io.tmpdir}/autoheal-runs}") String workspacesRoot) {
    return new JGitWorkspaceManager(Path.of(workspacesRoot));
  }
}
