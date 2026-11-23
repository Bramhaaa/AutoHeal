package com.autoheal.demo.inventory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

@Service
public class InventoryService {

  private final Map<String, Integer> stockBySku = new ConcurrentHashMap<>();

  public void setStock(String sku, int quantity) {
    stockBySku.put(sku, quantity);
  }

  public int getStock(String sku) {
    return stockBySku.getOrDefault(sku, 0);
  }

  public void decrement(String sku, int quantity) {
    stockBySku.compute(
        sku,
        (key, current) -> {
          int available = current == null ? 0 : current;
          if (available < quantity) {
            throw new IllegalStateException("Insufficient stock for SKU: " + sku);
          }
          return available - quantity;
        });
  }
}
