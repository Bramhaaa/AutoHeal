package com.autoheal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.autoheal.domain.EmbeddingClient;
import com.autoheal.domain.IncidentMemoryEntry;
import com.autoheal.domain.IncidentMemoryRepository;
import com.autoheal.domain.IncidentMemorySource;
import com.autoheal.domain.LlmException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class IncidentMemoryServiceTest {

  private IncidentMemoryRepository repository;
  private EmbeddingClient embeddingClient;
  private IncidentMemoryService service;

  @BeforeEach
  void setUp() {
    repository = mock(IncidentMemoryRepository.class);
    embeddingClient = mock(EmbeddingClient.class);
    service = new IncidentMemoryService(repository, embeddingClient);
  }

  @Test
  void seedsCorpusOnceWhenEmptyAndSkipsWhenAlreadyPopulated() {
    float[] vec = new float[EmbeddingClient.EMBEDDING_DIMENSION];
    vec[0] = 1.0f;
    when(repository.countBySource(IncidentMemorySource.SEED)).thenReturn(0L, 30L);
    when(embeddingClient.embed(any())).thenReturn(vec);

    int insertedFirst = service.seedIfEmpty();
    int insertedSecond = service.seedIfEmpty();

    assertThat(insertedFirst).isEqualTo(IncidentMemorySeedCatalog.entries().size());
    assertThat(insertedFirst).isGreaterThanOrEqualTo(30);
    assertThat(insertedSecond).isZero();
    verify(repository, times(insertedFirst)).save(any(), eq(vec));
  }

  @Test
  void retrievesTopTwoWhenRagEnabledAndSkipsWhenDisabled() {
    float[] vec = new float[EmbeddingClient.EMBEDDING_DIMENSION];
    vec[0] = 1.0f;
    IncidentMemoryEntry match =
        new IncidentMemoryEntry(
            UUID.randomUUID(),
            IncidentMemorySource.SEED,
            null,
            "NullPointerException on optional field",
            "Add null guard",
            0.91);
    when(embeddingClient.embed("NullPointerException")).thenReturn(vec);
    when(repository.findTopSimilar(vec, 2, "bug-42")).thenReturn(List.of(match));

    List<IncidentMemoryEntry> disabled =
        service.retrieveSimilar("NullPointerException", "bug-42", false);
    assertThat(disabled).isEmpty();
    verify(embeddingClient, never()).embed(any());

    List<IncidentMemoryEntry> enabled =
        service.retrieveSimilar("NullPointerException", "bug-42", true);
    assertThat(enabled).containsExactly(match);
  }

  @Test
  void degradesGracefullyWhenEmbeddingClientFails() {
    when(embeddingClient.embed(any())).thenThrow(new LlmException("API key missing"));

    List<IncidentMemoryEntry> matches = service.retrieveSimilar("NullPointerException", null, true);
    assertThat(matches).isEmpty();

    // Recording memory also degrades without throwing so verified runs stay green
    service.recordVerifiedResolution("scenario-1", "NullPointerException", "Fixed null check");
    verify(repository, never()).save(any(), any());
  }

  @Test
  void recordsVerifiedResolutionAsSelfPopulatingMemory() {
    float[] vec = new float[EmbeddingClient.EMBEDDING_DIMENSION];
    vec[1] = 1.0f;
    when(embeddingClient.embed("NullPointerException")).thenReturn(vec);

    service.recordVerifiedResolution("scenario-1", "NullPointerException", "Added null guard");

    ArgumentCaptor<IncidentMemoryEntry> captor = ArgumentCaptor.forClass(IncidentMemoryEntry.class);
    verify(repository).save(captor.capture(), eq(vec));
    assertThat(captor.getValue().source()).isEqualTo(IncidentMemorySource.VERIFIED_RUN);
    assertThat(captor.getValue().originBugId()).isEqualTo("scenario-1");
    assertThat(captor.getValue().resolutionSummary()).isEqualTo("Added null guard");
  }
}
