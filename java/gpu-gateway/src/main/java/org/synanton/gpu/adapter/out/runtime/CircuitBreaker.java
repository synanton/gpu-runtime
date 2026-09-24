package org.synanton.gpu.adapter.out.runtime;

import java.time.Duration;
import java.time.Instant;

/**
 * Minimal per-provider circuit breaker (T-K8S-45, PR #15 review §6:
 * "provider circuit opens → subsequent request denied without provider call").
 *
 * <p>Opens after {@code failureThreshold} consecutive failures; while open, calls are
 * denied immediately. After {@code resetSeconds} the next call is a half-open trial:
 * success closes the circuit, failure re-opens it. Only provider-side failures
 * (connect / timeout / 5xx) count — client errors (4xx) do not trip the breaker.
 *
 * <p>Not thread-local: one instance per provider id, owned by
 * {@link ProviderRuntimeRegistry}. State is in-memory (per Gateway replica); the
 * persisted control-state variant is T-K8S-38 follow-up.
 */
public class CircuitBreaker {

    /** Thrown by {@link #beforeCall()} while the circuit is open. */
    @SuppressWarnings("serial")
    public static class CircuitOpenException extends RuntimeException {
        public CircuitOpenException(String message) {
            super(message);
        }
    }

    private final int failureThreshold;
    private final Duration resetAfter;

    private int consecutiveFailures = 0;
    private Instant openedAt = null;

    public CircuitBreaker(int failureThreshold, Duration resetAfter) {
        this.failureThreshold = failureThreshold > 0 ? failureThreshold : 5;
        this.resetAfter = resetAfter != null ? resetAfter : Duration.ofSeconds(60);
    }

    /** Denies immediately while open (before any provider call is made). */
    public synchronized void beforeCall() {
        if (openedAt == null) {
            return;
        }
        if (Instant.now().isBefore(openedAt.plus(resetAfter))) {
            throw new CircuitOpenException("circuit open");
        }
        // reset window elapsed: fall through as a half-open trial call
    }

    public synchronized void onSuccess() {
        consecutiveFailures = 0;
        openedAt = null;
    }

    public synchronized void onFailure() {
        consecutiveFailures++;
        if (openedAt == null && consecutiveFailures >= failureThreshold) {
            openedAt = Instant.now();
        } else if (openedAt != null && !Instant.now().isBefore(openedAt.plus(resetAfter))) {
            openedAt = Instant.now(); // half-open trial failed: re-open
        }
    }

    public synchronized boolean isOpen() {
        return openedAt != null && Instant.now().isBefore(openedAt.plus(resetAfter));
    }

    /** Test visibility. */
    synchronized int consecutiveFailures() {
        return consecutiveFailures;
    }
}
