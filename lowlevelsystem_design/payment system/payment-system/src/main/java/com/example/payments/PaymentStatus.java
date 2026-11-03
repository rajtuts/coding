package com.example.payments;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** Payment lifecycle. The transition table is the only place that decides what is legal. */
public enum PaymentStatus {
    CREATED, PROCESSING, UNKNOWN, AUTHORIZED, CAPTURED, PARTIALLY_REFUNDED, REFUNDED, VOIDED, FAILED;

    private static final Map<PaymentStatus, Set<PaymentStatus>> ALLOWED = new EnumMap<>(PaymentStatus.class);

    static {
        ALLOWED.put(CREATED, EnumSet.of(PROCESSING, FAILED));
        ALLOWED.put(PROCESSING, EnumSet.of(AUTHORIZED, CAPTURED, FAILED, UNKNOWN));
        ALLOWED.put(UNKNOWN, EnumSet.of(AUTHORIZED, CAPTURED, FAILED));
        ALLOWED.put(AUTHORIZED, EnumSet.of(CAPTURED, VOIDED, FAILED));
        ALLOWED.put(CAPTURED, EnumSet.of(PARTIALLY_REFUNDED, REFUNDED));
        ALLOWED.put(PARTIALLY_REFUNDED, EnumSet.of(PARTIALLY_REFUNDED, REFUNDED));
    }

    public boolean canTransitionTo(PaymentStatus next) {
        return ALLOWED.getOrDefault(this, EnumSet.noneOf(PaymentStatus.class)).contains(next);
    }

    /** What merchants see: internal in-flight states are all reported as PROCESSING. */
    public String external() {
        return (this == CREATED || this == UNKNOWN) ? PROCESSING.name() : name();
    }
}
