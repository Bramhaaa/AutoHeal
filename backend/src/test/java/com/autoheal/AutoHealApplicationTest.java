package com.autoheal;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(PostgresTestContainer.class)
class AutoHealApplicationTest {

  @Test
  void contextLoads() {}
}
