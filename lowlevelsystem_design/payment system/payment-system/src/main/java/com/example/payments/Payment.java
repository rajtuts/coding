package com.example.payments;

import java.time.Instant;

public record Payment(
        String id,
        String merchantId,
        String merchantOrderId,
        long amount,
        String currency,
        String paymentToken,
        boolean captureRequested,
        PaymentStatus status,
        String psp,
        String pspReference,
        String failureCode,
        int version,
        Instant createdAt,
        Instant updatedAt) {
}
