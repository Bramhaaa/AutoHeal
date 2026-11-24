package com.autoheal.domain;

import java.util.Objects;

public sealed interface LlmToolCall
    permits LlmToolCall.ListFilesCall,
        LlmToolCall.ReadFileCall,
        LlmToolCall.SearchCodeCall,
        LlmToolCall.ProposePatchCall {

  record ListFilesCall(String dir, String glob) implements LlmToolCall {}

  record ReadFileCall(String path, int startLine, int endLine) implements LlmToolCall {
    public ReadFileCall {
      Objects.requireNonNull(path, "path must not be null");
    }
  }

  record SearchCodeCall(String query, String glob) implements LlmToolCall {
    public SearchCodeCall {
      Objects.requireNonNull(query, "query must not be null");
    }
  }

  record ProposePatchCall(PatchProposal proposal) implements LlmToolCall {
    public ProposePatchCall {
      Objects.requireNonNull(proposal, "proposal must not be null");
    }
  }
}
