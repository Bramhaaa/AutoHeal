package com.autoheal.api;

public record ApiErrorResponse(String code, String message, String suggestedMode) {

  public static ApiErrorResponse of(String code, String message) {
    return new ApiErrorResponse(code, message, null);
  }

  public static ApiErrorResponse suggestReplay(String code, String message) {
    return new ApiErrorResponse(code, message, "REPLAY");
  }
}
