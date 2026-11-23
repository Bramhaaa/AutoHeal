package com.autoheal.demo.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class InventoryServiceTest {

  private static final int THREADS = 8;
  private static final int DECREMENTS_PER_THREAD = 10_000;
  private static final int TOTAL_DECREMENTS = THREADS * DECREMENTS_PER_THREAD;

  @Test
  void decrementsStockAtomicallyUnderConcurrentLoad() throws Exception {
    InventoryService inventoryService = new InventoryService();
    inventoryService.setStock("SKU-100", TOTAL_DECREMENTS);

    CountDownLatch ready = new CountDownLatch(THREADS);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<?>> futures = new ArrayList<>();

    try (ExecutorService executor = Executors.newFixedThreadPool(THREADS)) {
      for (int t = 0; t < THREADS; t++) {
        futures.add(
            executor.submit(
                () -> {
                  ready.countDown();
                  try {
                    start.await();
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                  }
                  for (int i = 0; i < DECREMENTS_PER_THREAD; i++) {
                    inventoryService.decrement("SKU-100", 1);
                  }
                }));
      }
      ready.await();
      start.countDown();
      for (Future<?> future : futures) {
        future.get();
      }
    }

    assertThat(inventoryService.getStock("SKU-100")).isZero();
  }
}
