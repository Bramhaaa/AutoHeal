package com.autoheal.domain;

public final class PathTraversalException extends RuntimeException {

  public PathTraversalException(String message) {
    super(message);
  }

  public PathTraversalException(String message, Throwable cause) {
    super(message, cause);
  }
}
