package com.autoheal.infra.llm;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "autoheal.llm.gemini")
public record GeminiProperties(
    String apiKey,
    String baseUrl,
    String model,
    BigDecimal promptPricePerMillion,
    BigDecimal completionPricePerMillion) {}
