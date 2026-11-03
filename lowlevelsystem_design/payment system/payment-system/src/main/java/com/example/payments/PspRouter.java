package com.example.payments;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Picks a PSP for NEW payments: first in priority order whose circuit is closed.
 * (A production router would also weigh success rate, cost and payment method.)
 * Operations on existing payments always go to the PSP that owns them, via byName().
 */
public class PspRouter {
    private final List<PspAdapter> adapters;
    private final Map<String, PspAdapter> byName = new HashMap<>();
    private final Map<String, CircuitBreaker> breakers = new HashMap<>();

    public PspRouter(List<PspAdapter> priorityOrder, int failureThreshold, Duration openFor) {
        this.adapters = List.copyOf(priorityOrder);
        for (PspAdapter a : adapters) {
            byName.put(a.name(), a);
            breakers.put(a.name(), new CircuitBreaker(failureThreshold, openFor));
        }
    }

    public Optional<PspAdapter> choose() {
        return adapters.stream().filter(a -> breakers.get(a.name()).allowRequest()).findFirst();
    }

    public PspAdapter byName(String name) {
        PspAdapter a = byName.get(name);
        if (a == null) throw new IllegalArgumentException("Unknown PSP " + name);
        return a;
    }

    public CircuitBreaker breaker(String name) { return breakers.get(name); }
}
