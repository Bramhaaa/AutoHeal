package com.autoheal.demo.pagination;

import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class PaginationService {

  public <T> List<T> getPage(List<T> items, int page, int pageSize) {
    if (page < 0 || pageSize <= 0) {
      throw new IllegalArgumentException("page must be >= 0 and pageSize must be > 0");
    }
    int fromIndex = page * pageSize;
    if (fromIndex >= items.size()) {
      return List.of();
    }
    int toIndex = Math.min(fromIndex + pageSize, items.size());
    return List.copyOf(items.subList(fromIndex, toIndex));
  }
}
