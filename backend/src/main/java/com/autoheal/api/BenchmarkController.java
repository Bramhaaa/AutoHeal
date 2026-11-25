package com.autoheal.api;

import com.autoheal.domain.BenchmarkSummary;
import com.autoheal.service.BenchmarkReportService;
import java.util.Objects;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/benchmarks")
public class BenchmarkController {

  private final BenchmarkReportService benchmarkReportService;

  public BenchmarkController(BenchmarkReportService benchmarkReportService) {
    this.benchmarkReportService =
        Objects.requireNonNull(benchmarkReportService, "benchmarkReportService must not be null");
  }

  @GetMapping("/summary")
  public BenchmarkSummary getSummary() {
    return benchmarkReportService.getLatestSummary();
  }
}
