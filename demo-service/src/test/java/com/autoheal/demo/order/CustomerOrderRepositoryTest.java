package com.autoheal.demo.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

@DataJpaTest
class CustomerOrderRepositoryTest {

  @Autowired private CustomerOrderRepository repository;

  @Test
  void findsOrdersMatchingOrderStatus() {
    repository.save(new CustomerOrder("alice@example.com", "PAID"));
    repository.save(new CustomerOrder("bob@example.com", "PENDING"));
    repository.save(new CustomerOrder("carol@example.com", "PAID"));

    List<CustomerOrder> paidOrders = repository.findByStatus("PAID");

    assertThat(paidOrders)
        .extracting(CustomerOrder::getCustomerEmail)
        .containsExactlyInAnyOrder("alice@example.com", "carol@example.com");
  }
}
