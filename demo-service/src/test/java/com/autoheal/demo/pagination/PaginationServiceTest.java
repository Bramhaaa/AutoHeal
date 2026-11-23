package com.autoheal.demo.pagination;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class PaginationServiceTest {

  private final PaginationService paginationService = new PaginationService();

  @Test
  void returnsExactPageSizeForFullPage() {
    List<String> items = List.of("a", "b", "c", "d", "e");

    assertThat(paginationService.getPage(items, 0, 3)).containsExactly("a", "b", "c");
  }

  @Test
  void returnsRemainingItemsOnLastPage() {
    List<String> items = List.of("a", "b", "c", "d", "e");

    assertThat(paginationService.getPage(items, 1, 3)).containsExactly("d", "e");
  }

  @Test
  void returnsEmptyListWhenPageIsOutOfRange() {
    List<String> items = List.of("a", "b");

    assertThat(paginationService.getPage(items, 2, 2)).isEmpty();
  }
}
