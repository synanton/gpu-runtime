package com.synanton.gpu.domain.model;

/**
 * Structured error attached to a failed or cancelled execution.
 * Diagnostic information only; user-facing rendering belongs to SynAnton Core.
 */
public record ExecutionError(String code, String message, boolean retryable) {

    public static ExecutionError of(String code, String message, boolean retryable) {
        return new ExecutionError(code, message, retryable);
    }

    public static ExecutionError nonRetryable(String code, String message) {
        return new ExecutionError(code, message, false);
    }

    public static ExecutionError retryable(String code, String message) {
        return new ExecutionError(code, message, true);
    }
}
