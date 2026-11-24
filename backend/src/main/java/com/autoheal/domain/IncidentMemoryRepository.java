package com.autoheal.domain;

import java.util.List;

public interface IncidentMemoryRepository {

  void save(IncidentMemoryEntry entry, float[] embedding);

  /**
   * Retrieves up to {@code limit} most similar past incidents by cosine similarity, excluding any
   * row whose {@code origin_bug_id} matches {@code excludeOriginBugId} (eval leakage guard).
   */
  List<IncidentMemoryEntry> findTopSimilar(
      float[] queryEmbedding, int limit, String excludeOriginBugId);

  long countBySource(IncidentMemorySource source);
}
