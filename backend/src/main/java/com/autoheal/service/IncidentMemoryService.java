package com.autoheal.service;

import com.autoheal.domain.EmbeddingClient;
import com.autoheal.domain.IncidentMemoryEntry;
import com.autoheal.domain.IncidentMemoryRepository;
import com.autoheal.domain.IncidentMemorySource;
import com.autoheal.domain.LlmException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class IncidentMemoryService {

  private static final Logger log = LoggerFactory.getLogger(IncidentMemoryService.class);
  public static final int DEFAULT_TOP_K = 2;

  private final IncidentMemoryRepository repository;
  private final EmbeddingClient embeddingClient;

  public IncidentMemoryService(
      IncidentMemoryRepository repository, EmbeddingClient embeddingClient) {
    this.repository = Objects.requireNonNull(repository, "repository must not be null");
    this.embeddingClient =
        Objects.requireNonNull(embeddingClient, "embeddingClient must not be null");
  }

  public int seedIfEmpty() {
    if (repository.countBySource(IncidentMemorySource.SEED) > 0L) {
      return 0;
    }
    int inserted = 0;
    for (IncidentMemorySeedCatalog.SeedIncident seed : IncidentMemorySeedCatalog.entries()) {
      float[] embedding = embeddingClient.embed(seed.errorSignature());
      repository.save(
          new IncidentMemoryEntry(
              UUID.randomUUID(),
              IncidentMemorySource.SEED,
              null,
              seed.errorSignature(),
              seed.resolutionSummary(),
              0.0),
          embedding);
      inserted++;
    }
    return inserted;
  }

  public List<IncidentMemoryEntry> retrieveSimilar(
      String errorSignature, String excludeOriginBugId, boolean ragEnabled) {
    if (!ragEnabled || errorSignature == null || errorSignature.isBlank()) {
      return List.of();
    }
    try {
      float[] queryEmbedding = embeddingClient.embed(errorSignature);
      return repository.findTopSimilar(queryEmbedding, DEFAULT_TOP_K, excludeOriginBugId);
    } catch (LlmException e) {
      log.warn("Skipping RAG lookup because embedding call failed: {}", e.getMessage());
      return List.of();
    }
  }

  public void recordVerifiedResolution(
      String originBugId, String errorSignature, String resolutionSummary) {
    if (errorSignature == null
        || errorSignature.isBlank()
        || resolutionSummary == null
        || resolutionSummary.isBlank()) {
      return;
    }
    try {
      float[] embedding = embeddingClient.embed(errorSignature);
      repository.save(
          new IncidentMemoryEntry(
              UUID.randomUUID(),
              IncidentMemorySource.VERIFIED_RUN,
              originBugId,
              errorSignature,
              resolutionSummary,
              0.0),
          embedding);
    } catch (LlmException e) {
      log.warn("Skipping self-populating incident_memory write: {}", e.getMessage());
    }
  }
}
