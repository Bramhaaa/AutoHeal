package com.autoheal.api;

import com.autoheal.domain.EvalBugCase;
import com.autoheal.domain.EvalConfig;
import com.autoheal.service.EvalHarnessService;
import com.autoheal.service.EvalHarnessService.EvalRunReport;
import com.autoheal.service.EvalReportFormatter;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "autoheal.eval.enabled", havingValue = "true")
public class AutoHealEvalRunner implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(AutoHealEvalRunner.class);

  private final EvalHarnessService evalHarnessService;
  private final EvalReportFormatter reportFormatter;
  private final ObjectMapper objectMapper;
  private final String model;
  private final String corpusPath;
  private final String outputDir;
  private final int repeatCount;

  public AutoHealEvalRunner(
      EvalHarnessService evalHarnessService,
      EvalReportFormatter reportFormatter,
      ObjectMapper objectMapper,
      @Value("${autoheal.llm.gemini.model:gemini-2.5-flash}") String model,
      @Value("${autoheal.eval.corpus:eval-corpus/bugs.json}") String corpusPath,
      @Value("${autoheal.eval.output-dir:eval-results}") String outputDir,
      @Value("${autoheal.eval.repeats:3}") int repeatCount) {
    this.evalHarnessService = Objects.requireNonNull(evalHarnessService, "evalHarnessService");
    this.reportFormatter = Objects.requireNonNull(reportFormatter, "reportFormatter");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    this.model = Objects.requireNonNull(model, "model");
    this.corpusPath = Objects.requireNonNull(corpusPath, "corpusPath");
    this.outputDir = Objects.requireNonNull(outputDir, "outputDir");
    this.repeatCount = repeatCount;
  }

  @Override
  public void run(ApplicationArguments args) throws Exception {
    Path corpusFile = Path.of(corpusPath);
    List<EvalBugCase> corpus =
        objectMapper.readValue(Files.readString(corpusFile), new TypeReference<>() {});
    List<EvalConfig> ablations =
        List.of(
            new EvalConfig("Full (RAG + 3 attempts + distiller)", true, 3, true),
            new EvalConfig("No RAG (3 attempts + distiller)", false, 3, true),
            new EvalConfig("Single-shot (RAG + 1 attempt)", true, 1, true),
            new EvalConfig("Raw logs (RAG + 3 attempts, no distiller)", true, 3, false));

    log.info(
        "Starting AutoHeal evaluation harness: {} bug(s), {} ablation(s), {} repeat(s)",
        corpus.size(),
        ablations.size(),
        repeatCount);
    EvalRunReport report =
        evalHarnessService.runAblationsAndPersist(model, corpus, ablations, repeatCount);
    reportFormatter.writeReports(Path.of(outputDir), model, report);
    log.info("Evaluation complete. Reports written to {}", outputDir);
  }
}
