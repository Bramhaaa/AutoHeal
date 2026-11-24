package com.autoheal.domain;

public interface EmbeddingClient {

  int EMBEDDING_DIMENSION = 768;

  /**
   * Computes a normalized dense embedding vector matching {@code incident_memory.embedding}.
   *
   * @param text input error signature or incident text
   * @return 768-dimensional embedding vector
   */
  float[] embed(String text);
}
