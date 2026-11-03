package com.example.payments;

import java.time.Instant;

public record Refund(
        String id,
        String paymentId,
        long amount,
        Status status,
        String pspReference,
        String idempotencyKey,
        Instant createdAt) {

    public enum Status { PENDING, SUCCEEDED, FAILED }
}
