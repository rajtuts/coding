package com.example.payments;

/** One implementation per provider (Razorpay, Stripe, Adyen, a bank, ...). */
public interface PspAdapter {

    String name();

    /** Our payment id is the reference, and doubles as the PSP-side idempotency key. */
    Result authorize(AuthRequest req);

    Result capture(String pspReference, long amount, String captureRef);

    /** refundId is the PSP-side idempotency key. */
    Result refund(String pspReference, long amount, String refundId);

    /** Lookup by OUR reference. Used to resolve payments whose outcome we never saw. */
    Result getStatus(String reference);

    record AuthRequest(String reference, long amount, String currency, String token, boolean capture) { }

    record Result(Status status, String pspReference, String errorCode) {
        public enum Status { AUTHORIZED, CAPTURED, REFUNDED, FAILED, PENDING, NOT_FOUND, UNKNOWN }
    }
}
