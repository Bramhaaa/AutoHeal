package com.autoheal.infra.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.autoheal.PostgresTestContainer;
import com.autoheal.domain.EmbeddingClient;
import com.autoheal.domain.IncidentMemoryEntry;
import com.autoheal.domain.IncidentMemoryRepository;
import com.autoheal.domain.IncidentMemorySource;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
@Import(PostgresTestContainer.class)
class JdbcIncidentMemoryRepositoryTest {

  private final IncidentMemoryRepository repository;
  private final JdbcClient jdbcClient;

  @Autowired
  JdbcIncidentMemoryRepositoryTest(IncidentMemoryRepository repository, JdbcClient jdbcClient) {
    this.repository = repository;
    this.jdbcClient = jdbcClient;
  }

  @BeforeEach
  void cleanTable() {
    jdbcClient.sql("DELETE FROM incident_memory").update();
  }

  @Test
  void retrievesTopSimilarByCosineSimilarityAndEnforcesEvalLeakageGuard() {
    float[] npeVec = basisVector(0);
    float[] nearNpeVec = blendedVector(0, 1, 0.9f, 0.1f);
    float[] raceVec = basisVector(2);

    repository.save(
        new IncidentMemoryEntry(
            UUID.randomUUID(),
            IncidentMemorySource.VERIFIED_RUN,
            "bug-leak-01",
            "NullPointerException in DiscountCalculator",
            "Guard nullable discount before dereference",
            0.0),
        npeVec);
    repository.save(
        new IncidentMemoryEntry(
            UUID.randomUUID(),
            IncidentMemorySource.SEED,
            null,
            "NullPointerException on optional payload field",
            "Use Objects.requireNonNullElse or null guard",
            0.0),
        nearNpeVec);
    repository.save(
        new IncidentMemoryEntry(
            UUID.randomUUID(),
            IncidentMemorySource.SEED,
            null,
            "Race condition in unsynchronized counter",
            "Use AtomicInteger or synchronized block",
            0.0),
        raceVec);

    assertThat(repository.countBySource(IncidentMemorySource.SEED)).isEqualTo(2L);
    assertThat(repository.countBySource(IncidentMemorySource.VERIFIED_RUN)).isEqualTo(1L);

    // Without leakage exclusion: exact match bug-leak-01 comes first
    List<IncidentMemoryEntry> unfiltered = repository.findTopSimilar(npeVec, 2, null);
    assertThat(unfiltered).hasSize(2);
    assertThat(unfiltered.get(0).originBugId()).isEqualTo("bug-leak-01");
    assertThat(unfiltered.get(0).similarityScore()).isGreaterThan(0.99);

    // With leakage guard: bug-leak-01 MUST be excluded even though similarity = 1.0
    List<IncidentMemoryEntry> guarded = repository.findTopSimilar(npeVec, 2, "bug-leak-01");
    assertThat(guarded).hasSize(2);
    assertThat(guarded).noneMatch(e -> "bug-leak-01".equals(e.originBugId()));
    assertThat(guarded.get(0).errorSignature())
        .isEqualTo("NullPointerException on optional payload field");
  }

  private static float[] basisVector(int index) {
    float[] v = new float[EmbeddingClient.EMBEDDING_DIMENSION];
    v[index] = 1.0f;
    return v;
  }

  private static float[] blendedVector(int i, int j, float wi, float wj) {
    float[] v = new float[EmbeddingClient.EMBEDDING_DIMENSION];
    v[i] = wi;
    v[j] = wj;
    return v;
  }
}
