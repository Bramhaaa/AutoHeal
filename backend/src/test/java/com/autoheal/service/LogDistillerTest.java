package com.autoheal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.autoheal.domain.DistilledLog;
import org.junit.jupiter.api.Test;

class LogDistillerTest {

  private final LogDistiller distiller = new LogDistiller();

  @Test
  void stripsMavenAndFrameworkNoiseWhileRetainingCausedByAppFramesAndAssertions() {
    StringBuilder raw = new StringBuilder();
    raw.append("[INFO] Scanning for projects...\n");
    raw.append("[INFO] Building autoheal-demo-service 0.1.0-SNAPSHOT\n");
    for (int i = 0; i < 40; i++) {
      raw.append("[INFO] Downloading from central: https://repo.maven.apache.org/pkg-")
          .append(i)
          .append(".jar\n");
      raw.append("[INFO] Downloaded from central: https://repo.maven.apache.org/pkg-")
          .append(i)
          .append(".jar (42 kB at 1.2 MB/s)\n");
    }
    raw.append(
        """
        [INFO] --- surefire:3.5.2:test (default-test) @ autoheal-demo-service ---
        [INFO] Running com.autoheal.demo.payment.PaymentServiceTest
        [ERROR] Tests run: 2, Failures: 0, Errors: 1, Skipped: 0, Time elapsed: 0.12 s <<< FAILURE! -- in com.autoheal.demo.payment.PaymentServiceTest
        [ERROR] com.autoheal.demo.payment.PaymentServiceTest.handlesNullDiscount -- Time elapsed: 0.01 s <<< ERROR!
        java.lang.RuntimeException: Payment failed
        \tat com.autoheal.demo.payment.PaymentService.charge(PaymentService.java:24)
        \tat java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:103)
        \tat java.base/java.lang.reflect.Method.invoke(Method.java:580)
        \tat org.junit.platform.commons.util.ReflectionUtils.invokeMethod(ReflectionUtils.java:767)
        \tat org.junit.jupiter.engine.execution.MethodInvocation.proceed(MethodInvocation.java:60)
        \tat org.springframework.test.context.junit.jupiter.SpringExtension.beforeEach(SpringExtension.java:120)
        \tat sun.reflect.NativeMethodAccessorImpl.invoke0(Native Method)
        Caused by: java.lang.NullPointerException: Cannot invoke "Discount.percentage()" because "discount" is null
        \tat com.autoheal.demo.payment.PaymentService.applyDiscount(PaymentService.java:37)
        \tat com.autoheal.demo.payment.PaymentServiceTest.handlesNullDiscount(PaymentServiceTest.java:19)
        \tat org.junit.jupiter.engine.descriptor.TestMethodTestDescriptor.execute(TestMethodTestDescriptor.java:138)
        [INFO]
        [INFO] Results:
        [INFO]
        [ERROR] Errors:
        [ERROR]   PaymentServiceTest.handlesNullDiscount:19 » NullPointer Cannot invoke "Discount.percentage()" because "discount" is null
        [ERROR] Tests run: 2, Failures: 0, Errors: 1, Skipped: 0
        [INFO] BUILD FAILURE
        """);

    DistilledLog distilled = distiller.distill(raw.toString());

    assertThat(distilled.summary())
        .doesNotContain("Downloading from central")
        .doesNotContain("DirectMethodHandleAccessor")
        .doesNotContain("org.springframework.test")
        .doesNotContain("sun.reflect.NativeMethodAccessorImpl")
        .contains("java.lang.RuntimeException: Payment failed")
        .contains("Caused by: java.lang.NullPointerException")
        .contains("com.autoheal.demo.payment.PaymentService.charge(PaymentService.java:24)")
        .contains("com.autoheal.demo.payment.PaymentService.applyDiscount(PaymentService.java:37)")
        .contains("PaymentServiceTest.handlesNullDiscount:19")
        .contains("framework frames omitted");

    assertThat(distilled.failingTests())
        .contains("com.autoheal.demo.payment.PaymentServiceTest.handlesNullDiscount");
    assertThat(distilled.referencedFiles())
        .contains("PaymentService.java", "PaymentServiceTest.java");
    assertThat(distilled.reductionPercent()).isGreaterThanOrEqualTo(75.0);
  }

  @Test
  void retainsCompilerErrorsWithFileAndLineNumbers() {
    String raw =
        """
        [INFO] --- compiler:3.13.0:compile (default-compile) @ demo ---
        [INFO] Compiling 5 source files with javac to target/classes
        [ERROR] COMPILATION ERROR :
        [ERROR] /workspace/src/main/java/com/demo/OrderService.java:[42,15] cannot find symbol
          symbol:   method getTotal()
          location: variable order of type com.demo.Order
        [INFO] 1 error
        [INFO] BUILD FAILURE
        """;

    DistilledLog distilled = distiller.distill(raw);

    assertThat(distilled.summary())
        .contains("COMPILATION ERROR")
        .contains("/workspace/src/main/java/com/demo/OrderService.java:[42,15] cannot find symbol")
        .contains("symbol:   method getTotal()")
        .contains("location: variable order of type com.demo.Order");
    assertThat(distilled.referencedFiles()).contains("OrderService.java");
  }

  @Test
  void collapsesRepeatedStackFrames() {
    StringBuilder raw = new StringBuilder("java.lang.StackOverflowError\n");
    for (int i = 0; i < 50; i++) {
      raw.append("\tat com.demo.Recursive.loop(Recursive.java:10)\n");
    }

    DistilledLog distilled = distiller.distill(raw.toString());

    assertThat(distilled.summary())
        .contains("java.lang.StackOverflowError")
        .contains("com.demo.Recursive.loop(Recursive.java:10)")
        .contains("repeated 49 more times");
  }

  @Test
  void capsInputAt500KbAndHandlesEmptyInput() {
    DistilledLog empty = distiller.distill("");
    assertThat(empty.summary()).isEmpty();
    assertThat(empty.reductionPercent()).isEqualTo(0.0);

    String hugeNoise = "[INFO] Downloading from central: x.jar\n".repeat(20_000);
    DistilledLog distilled = distiller.distill(hugeNoise);
    assertThat(distilled.rawLength()).isEqualTo(LogDistiller.MAX_RAW_CHARS);
    assertThat(distilled.distilledLength()).isLessThan(1_000);
  }
}
