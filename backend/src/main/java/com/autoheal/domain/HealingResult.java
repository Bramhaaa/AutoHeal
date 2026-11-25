package com.autoheal.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record HealingResult(
    boolean succeeded,
    String failureReason,
    String rootCauseSummary,
    String unifiedDiff,
    List<String> modifiedFiles,
    List<String> createdFiles,
    Map<String, String> patchedFileContents,
    List<AttemptRecord> attempts,
    List<LlmUsage> llmCalls) {

  public HealingResult {
    modifiedFiles =
        List.copyOf(Objects.requireNonNull(modifiedFiles, "modifiedFiles must not be null"));
    createdFiles =
        List.copyOf(Objects.requireNonNull(createdFiles, "createdFiles must not be null"));
    patchedFileContents =
        Map.copyOf(
            Objects.requireNonNull(patchedFileContents, "patchedFileContents must not be null"));
    attempts = List.copyOf(Objects.requireNonNull(attempts, "attempts must not be null"));
    llmCalls = List.copyOf(Objects.requireNonNull(llmCalls, "llmCalls must not be null"));
  }

  public static HealingResult succeeded(
      String rootCauseSummary,
      String unifiedDiff,
      List<AttemptRecord> attempts,
      List<LlmUsage> llmCalls) {
    return succeeded(
        rootCauseSummary, unifiedDiff, List.of(), List.of(), Map.of(), attempts, llmCalls);
  }

  public static HealingResult succeeded(
      String rootCauseSummary,
      String unifiedDiff,
      List<String> modifiedFiles,
      List<String> createdFiles,
      Map<String, String> patchedFileContents,
      List<AttemptRecord> attempts,
      List<LlmUsage> llmCalls) {
    return new HealingResult(
        true,
        null,
        rootCauseSummary,
        unifiedDiff,
        modifiedFiles,
        createdFiles,
        patchedFileContents,
        attempts,
        llmCalls);
  }

  public static HealingResult failed(
      String failureReason, List<AttemptRecord> attempts, List<LlmUsage> llmCalls) {
    return new HealingResult(
        false, failureReason, null, "", List.of(), List.of(), Map.of(), attempts, llmCalls);
  }

  public int totalPromptTokens() {
    return llmCalls.stream().mapToInt(LlmUsage::promptTokens).sum();
  }

  public int totalCompletionTokens() {
    return llmCalls.stream().mapToInt(LlmUsage::completionTokens).sum();
  }

  public BigDecimal totalCostUsd() {
    return llmCalls.stream()
        .map(LlmUsage::estimatedCostUsd)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }
}
