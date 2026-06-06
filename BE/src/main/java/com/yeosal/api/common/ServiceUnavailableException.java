package com.yeosal.api.common;

/**
 * Caller should retry shortly — used when an in-flight cache rebuild or
 * background renderer owns a single-flight lock and no stale artifact is
 * available to serve. Maps to HTTP 503 with {@code Retry-After} via
 * {@link ApiExceptionHandler}.
 */
public class ServiceUnavailableException extends RuntimeException {
    public ServiceUnavailableException(String message) {
        super(message);
    }
}
