package com.autoheal.api;

import com.autoheal.domain.BenchmarkReportNotFoundException;
import com.autoheal.domain.DailyBudgetExceededException;
import com.autoheal.domain.IncidentRunNotFoundException;
import com.autoheal.domain.QueueCapacityExceededException;
import com.autoheal.domain.RateLimitExceededException;
import com.autoheal.domain.ReplayNotAvailableException;
import com.autoheal.domain.ScenarioNotFoundException;
import com.autoheal.domain.WebhookSignatureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler({
    ScenarioNotFoundException.class,
    IncidentRunNotFoundException.class,
    BenchmarkReportNotFoundException.class
  })
  public ResponseEntity<ApiErrorResponse> handleNotFound(RuntimeException ex) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(ApiErrorResponse.of("NOT_FOUND", ex.getMessage()));
  }

  @ExceptionHandler(ReplayNotAvailableException.class)
  public ResponseEntity<ApiErrorResponse> handleReplayUnavailable(ReplayNotAvailableException ex) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(ApiErrorResponse.of("REPLAY_NOT_AVAILABLE", ex.getMessage()));
  }

  @ExceptionHandler({RateLimitExceededException.class, DailyBudgetExceededException.class})
  public ResponseEntity<ApiErrorResponse> handleTooManyRequests(RuntimeException ex) {
    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
        .body(ApiErrorResponse.suggestReplay("RATE_OR_BUDGET_LIMIT", ex.getMessage()));
  }

  @ExceptionHandler(QueueCapacityExceededException.class)
  public ResponseEntity<ApiErrorResponse> handleQueueFull(QueueCapacityExceededException ex) {
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .body(ApiErrorResponse.suggestReplay("QUEUE_FULL", ex.getMessage()));
  }

  @ExceptionHandler(WebhookSignatureException.class)
  public ResponseEntity<ApiErrorResponse> handleWebhookSignature(WebhookSignatureException ex) {
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
        .body(ApiErrorResponse.of("INVALID_WEBHOOK_SIGNATURE", ex.getMessage()));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ApiErrorResponse> handleBadRequest(IllegalArgumentException ex) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(ApiErrorResponse.of("BAD_REQUEST", ex.getMessage()));
  }
}
