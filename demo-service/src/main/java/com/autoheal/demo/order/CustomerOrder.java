package com.autoheal.demo.order;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

@Entity
public class CustomerOrder {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private String customerEmail;
  private String orderStatus;

  protected CustomerOrder() {}

  public CustomerOrder(String customerEmail, String orderStatus) {
    this.customerEmail = customerEmail;
    this.orderStatus = orderStatus;
  }

  public Long getId() {
    return id;
  }

  public String getCustomerEmail() {
    return customerEmail;
  }

  public String getOrderStatus() {
    return orderStatus;
  }
}
