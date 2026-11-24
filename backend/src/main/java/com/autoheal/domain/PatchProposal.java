package com.autoheal.domain;

import java.util.List;
import java.util.Objects;

public record PatchProposal(
    List<SearchReplaceEdit> edits, List<NewTestFile> newTestFiles, String rootCauseSummary) {

  public PatchProposal {
    edits = List.copyOf(Objects.requireNonNull(edits, "edits must not be null"));
    newTestFiles =
        List.copyOf(Objects.requireNonNull(newTestFiles, "newTestFiles must not be null"));
    Objects.requireNonNull(rootCauseSummary, "rootCauseSummary must not be null");
  }
}
