package com.autoheal.infra.persistence;

import com.autoheal.domain.EmbeddingClient;
import com.autoheal.domain.IncidentMemoryEntry;
import com.autoheal.domain.IncidentMemoryRepository;
import com.autoheal.domain.IncidentMemorySource;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcIncidentMemoryRepository implements IncidentMemoryRepository {

  private final JdbcClient jdbcClient;

  public JdbcIncidentMemoryRepository(JdbcClient jdbcClient) {
    this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
  }

  @Override
  public void save(IncidentMemoryEntry entry, float[] embedding) {
    Objects.requireNonNull(entry, "entry must not be null");
    String vectorLiteral = toVectorLiteral(embedding);

    jdbcClient
        .sql(
            """
            INSERT INTO incident_memory (
                id, source, origin_bug_id, error_signature, resolution_summary, embedding
            ) VALUES (
                :id, :source, :originBugId, :errorSignature, :resolutionSummary,
                CAST(:embedding AS vector)
            )
            """)
        .param("id", entry.id())
        .param("source", entry.source().name())
        .param("originBugId", entry.originBugId())
        .param("errorSignature", entry.errorSignature())
        .param("resolutionSummary", entry.resolutionSummary())
        .param("embedding", vectorLiteral)
        .update();
  }

  @Override
  public List<IncidentMemoryEntry> findTopSimilar(
      float[] queryEmbedding, int limit, String excludeOriginBugId) {
    if (limit <= 0) {
      return List.of();
    }
    String vectorLiteral = toVectorLiteral(queryEmbedding);

    return jdbcClient
        .sql(
            """
            SELECT id, source, origin_bug_id, error_signature, resolution_summary,
                   1 - (embedding <=> CAST(:queryEmbedding AS vector)) AS similarity
            FROM incident_memory
            WHERE embedding IS NOT NULL
              AND (:excludeOriginBugId::text IS NULL
                   OR origin_bug_id IS NULL
                   OR origin_bug_id <> :excludeOriginBugId::text)
            ORDER BY embedding <=> CAST(:queryEmbedding AS vector) ASC
            LIMIT :limit
            """)
        .param("queryEmbedding", vectorLiteral)
        .param("excludeOriginBugId", excludeOriginBugId)
        .param("limit", limit)
        .query(
            (rs, rowNum) ->
                new IncidentMemoryEntry(
                    rs.getObject("id", UUID.class),
                    IncidentMemorySource.valueOf(rs.getString("source")),
                    rs.getString("origin_bug_id"),
                    rs.getString("error_signature"),
                    rs.getString("resolution_summary"),
                    rs.getDouble("similarity")))
        .list();
  }

  @Override
  public long countBySource(IncidentMemorySource source) {
    Objects.requireNonNull(source, "source must not be null");
    Long count =
        jdbcClient
            .sql("SELECT COUNT(*) FROM incident_memory WHERE source = :source")
            .param("source", source.name())
            .query(Long.class)
            .single();
    return count == null ? 0L : count;
  }

  private static String toVectorLiteral(float[] embedding) {
    Objects.requireNonNull(embedding, "embedding must not be null");
    if (embedding.length != EmbeddingClient.EMBEDDING_DIMENSION) {
      throw new IllegalArgumentException(
          "Embedding must have dimension "
              + EmbeddingClient.EMBEDDING_DIMENSION
              + ", got "
              + embedding.length);
    }
    StringBuilder sb = new StringBuilder(embedding.length * 8);
    sb.append('[');
    for (int i = 0; i < embedding.length; i++) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append(embedding[i]);
    }
    sb.append(']');
    return sb.toString();
  }
}
