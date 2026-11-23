package com.autoheal.demo.order;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CustomerOrderRepository extends JpaRepository<CustomerOrder, Long> {

  @Query("SELECT o FROM CustomerOrder o WHERE o.orderStatus = :status")
  List<CustomerOrder> findByStatus(@Param("status") String status);
}
