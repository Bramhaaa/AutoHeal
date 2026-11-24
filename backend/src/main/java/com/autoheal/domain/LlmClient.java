package com.autoheal.domain;

import java.util.List;

public interface LlmClient {

  LlmTurnResponse nextAction(List<LlmMessage> conversation);
}
