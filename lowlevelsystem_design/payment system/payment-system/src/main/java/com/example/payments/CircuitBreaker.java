package com.example.payments;

import java.time.Duration;
import java.time.Instant;

/** Opens after N consecutive failures; after the cool-down, lets traffic probe again (half-open). */
public class CircuitBreaker {
    private final int failureThreshold;
    private final Duration openFor;
    private int consecutiveFailures;
    private Instant openedAt;

    public CircuitBreaker(int failureThreshold, Duration openFor) {
        this.failureThreshold = failureThreshold;
        this.openFor = openFor;
    }

    public synchronized boolean allowRequest() {
        return openedAt == null || Instant.now().isAfter(openedAt.plus(openFor));
    }

    public synchronized boolean isOpen() { return !allowRequest(); }

    public synchronized void recordSuccess() {
        consecutiveFailures = 0;
        openedAt = null;
    }

    public synchronized void recordFailure() {
        if (++consecutiveFailures >= failureThreshold) openedAt = Instant.now();
    }
}
