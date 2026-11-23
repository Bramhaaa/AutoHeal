package com.autoheal.infra.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.transport.DockerHttpClient;
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

public final class DockerSandboxClientFactory {

  private DockerSandboxClientFactory() {}

  public static DockerClient createDefault() {
    DefaultDockerClientConfig.Builder builder =
        DefaultDockerClientConfig.createDefaultConfigBuilder();
    if (System.getenv("DOCKER_HOST") == null) {
      String detectedHost = detectLocalSocketUri();
      if (detectedHost != null) {
        builder.withDockerHost(detectedHost);
      }
    }
    DockerClientConfig config = builder.build();
    DockerHttpClient httpClient =
        new ZerodepDockerHttpClient.Builder()
            .dockerHost(config.getDockerHost())
            .sslConfig(config.getSSLConfig())
            .maxConnections(16)
            .connectionTimeout(Duration.ofSeconds(10))
            .responseTimeout(Duration.ofSeconds(90))
            .build();
    return DockerClientImpl.getInstance(config, httpClient);
  }

  private static String detectLocalSocketUri() {
    Path defaultSock = Path.of("/var/run/docker.sock");
    if (Files.exists(defaultSock)) {
      return "unix:///var/run/docker.sock";
    }
    Path macDesktopSock = Path.of(System.getProperty("user.home"), ".docker", "run", "docker.sock");
    if (Files.exists(macDesktopSock)) {
      return "unix://" + macDesktopSock;
    }
    return null;
  }
}
