package com.autoheal.infra.docker;

import com.autoheal.domain.SandboxRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DockerSandboxConfig {

  @Bean
  SandboxRunner sandboxRunner() {
    return new DockerSandboxRunner(DockerSandboxClientFactory.createDefault());
  }
}
