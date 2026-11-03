package com.example.payments;

import com.example.payments.PspAdapter.Result.Status;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;

/**
 * Orchestrates the payment lifecycle.
 *
 * Golden rule: a DB transaction is NEVER open while we wait on a PSP. The flow is
 *   tx1  reserve idempotency key + create payment (CREATED)
 *   tx2  lock payment, risk check, pick PSP, CREATED -> PROCESSING
 *        (no tx)   call the PSP
 *   tx3  lock payment, apply outcome + ledger + outbox + idempotency response, atomically
 */
public class PaymentService {

    public record CreatePaymentRequest(String merchantId, String merchantOrderId, long amount,
                                       String currency, String paymentToken, boolean capture) {
        String canonical() {
            return String.join("|", merchantId, merchantOrderId, String.valueOf(amount), currency,
                    paymentToken, String.valueOf(capture));
        }
    }

    public record ApiResponse(int httpStatus, String body, String paymentId) { }

    private record Reserved(Payment payment, ApiResponse replay) { }

    private record Claim(Payment payment, PspAdapter psp, ApiResponse done) { }

    private final Database db;
    private final PaymentRepository payments;
    private final IdempotencyRepository idempotency;
    private final OutboxRepository outbox;
    private final LedgerService ledger;
    private final RiskService risk;
    private final PspRouter router;

    public PaymentService(Database db, PaymentRepository payments, IdempotencyRepository idempotency,
                          OutboxRepository outbox, LedgerService ledger, RiskService risk, PspRouter router) {
        this.db = db;
        this.payments = payments;
        this.idempotency = idempotency;
        this.outbox = outbox;
        this.ledger = ledger;
        this.risk = risk;
        this.router = router;
    }

    // =====================================================================================
    // POST /v1/payments
    // =====================================================================================

    public ApiResponse createPayment(CreatePaymentRequest req, String idempotencyKey) {
        validate(req, idempotencyKey);
        String hash = Crypto.sha256Hex(req.canonical());
        String paymentId = Ids.newId("pay");

        // ---- tx1: reserve the idempotency key and create the payment ----
        Reserved reserved;
        try {
            reserved = db.inTx(c -> reserve(c, req, idempotencyKey, hash, paymentId));
        } catch (Database.DataAccessException e) {
            if (e.isConcurrencyConflict()) {
                throw new ApiException(409, "request_in_progress", "Same Idempotency-Key is being processed");
            }
            throw e;
        }
        if (reserved.replay() != null) return reserved.replay();

        // ---- tx2: claim (risk check, routing) ----
        Claim claim = db.inTx(c -> claim(c, paymentId));
        if (claim.done() != null) return claim.done();

        // ---- no transaction: talk to the PSP ----
        Payment p = claim.payment();
        PspAdapter psp = claim.psp();
        CircuitBreaker breaker = router.breaker(psp.name());
        PspAdapter.Result outcome;
        try {
            outcome = psp.authorize(new PspAdapter.AuthRequest(
                    p.id(), p.amount(), p.currency(), p.paymentToken(), p.captureRequested()));
            breaker.recordSuccess();
        } catch (PspException e) {
            breaker.recordFailure();
            outcome = e.isOutcomeUnknown()
                    ? new PspAdapter.Result(Status.UNKNOWN, null, "PSP_TIMEOUT")        // may have succeeded!
                    : new PspAdapter.Result(Status.FAILED, null, "PSP_UNAVAILABLE");    // request never left
        }

        // ---- tx3: persist the outcome ----
        final PspAdapter.Result finalOutcome = outcome;
        Payment settled = db.inTx(c -> applyPspResult(c, paymentId, finalOutcome, "api"));
        return response(settled);
    }

    private Reserved reserve(Connection c, CreatePaymentRequest req, String key, String hash, String paymentId)
            throws SQLException {
        try {
            idempotency.insert(c, req.merchantId(), key, hash, paymentId);
        } catch (SQLException e) {
            if (!Database.isUniqueViolation(e)) throw e;
            IdempotencyRepository.Record rec = idempotency.find(c, req.merchantId(), key)
                    .orElseThrow(() -> new ApiException(409, "request_in_progress", "Retry shortly"));
            if (!rec.requestHash().equals(hash)) {
                throw new ApiException(422, "idempotency_key_reuse", "Idempotency-Key reused with a different payload");
            }
            if ("COMPLETED".equals(rec.status())) {
                return new Reserved(null, new ApiResponse(rec.responseCode(), rec.responseBody(), rec.paymentId()));
            }
            throw new ApiException(409, "request_in_progress", "Same Idempotency-Key is being processed");
        }

        Instant now = Instant.now();
        Payment p = new Payment(paymentId, req.merchantId(), req.merchantOrderId(), req.amount(), req.currency(),
                req.paymentToken(), req.capture(), PaymentStatus.CREATED, null, null, null, 0, now, now);
        try {
            payments.insert(c, p);
        } catch (SQLException e) {
            if (Database.isUniqueViolation(e)) {
                throw new ApiException(409, "duplicate_order", "merchant_order_id already used: " + req.merchantOrderId());
            }
            throw e;
        }
        payments.addEvent(c, paymentId, null, PaymentStatus.CREATED, "api", null);
        return new Reserved(p, null);
    }

    private Claim claim(Connection c, String paymentId) throws SQLException {
        Payment p = payments.find(c, paymentId, true).orElseThrow();   // row lock
        if (p.status() != PaymentStatus.CREATED) return new Claim(p, null, response(c, p));

        if (risk.score(p) == RiskService.Decision.DECLINE) {
            return new Claim(null, null, failEarly(c, p, "RISK_DECLINED", "{\"reason\":\"risk\"}"));
        }
        PspAdapter psp = router.choose().orElse(null);
        if (psp == null) {
            return new Claim(null, null, failEarly(c, p, "NO_PSP_AVAILABLE", "{\"reason\":\"all circuits open\"}"));
        }
        payments.annotate(c, p.id(), psp.name(), null, null);
        Payment processing = payments.transition(c, p, PaymentStatus.PROCESSING, "api", Json.obj("psp", psp.name()));
        return new Claim(processing, psp, null);
    }

    private ApiResponse failEarly(Connection c, Payment p, String code, String meta) throws SQLException {
        payments.annotate(c, p.id(), null, null, code);
        Payment failed = payments.transition(c, p, PaymentStatus.FAILED, "api", meta);
        outbox.add(c, "payment.events", failed.id(), eventJson(failed));
        return response(c, failed);
    }

    // =====================================================================================
    // Applying a PSP outcome. ONE code path used by: API sync flow, webhooks, sweeper.
    // =====================================================================================

    /** Joins the caller's transaction (the webhook handler uses this to make dedupe + apply atomic). */
    public Payment applyPspResult(Connection c, String paymentId, PspAdapter.Result r, String actor)
            throws SQLException {
        Payment p = payments.find(c, paymentId, true)
                .orElseThrow(() -> new ApiException(404, "payment_not_found", paymentId));

        PaymentStatus target = switch (r.status()) {
            case AUTHORIZED -> PaymentStatus.AUTHORIZED;
            case CAPTURED -> PaymentStatus.CAPTURED;
            case FAILED -> PaymentStatus.FAILED;
            case PENDING, UNKNOWN -> PaymentStatus.UNKNOWN;
            case NOT_FOUND, REFUNDED -> null;
        };

        // Stale, duplicate or out-of-order information is ignored by the transition table.
        if (target == null || target == p.status() || !p.status().canTransitionTo(target)) {
            return p;
        }

        payments.annotate(c, p.id(), null, r.pspReference(), target == PaymentStatus.FAILED ? r.errorCode() : null);
        Payment updated = payments.transition(c, p, target, actor,
                Json.obj("psp_status", r.status().name(), "error", r.errorCode()));

        if (target == PaymentStatus.CAPTURED) ledger.postCapture(c, updated);
        if (target != PaymentStatus.UNKNOWN) outbox.add(c, "payment.events", updated.id(), eventJson(updated));

        response(c, updated);   // completes the idempotency record if the original request is still open
        return updated;
    }

    public Payment applyPspResult(String paymentId, PspAdapter.Result r, String actor) {
        return db.inTx(c -> applyPspResult(c, paymentId, r, actor));
    }

    // =====================================================================================
    // POST /v1/payments/{id}/capture   (second leg of auth-then-capture)
    // =====================================================================================

    public ApiResponse capture(String paymentId, long amount) {
        Payment p = db.inTx(c -> payments.find(c, paymentId, false)
                .orElseThrow(() -> new ApiException(404, "payment_not_found", paymentId)));
        if (p.status() != PaymentStatus.AUTHORIZED) {
            throw new ApiException(409, "invalid_state", "Cannot capture a payment in state " + p.status());
        }
        if (amount != p.amount()) {
            throw new ApiException(422, "partial_capture_unsupported", "This demo captures the full amount only");
        }
        PspAdapter.Result r;
        try {
            r = router.byName(p.psp()).capture(p.pspReference(), amount, p.id());   // PSP dedupes on p.id()
        } catch (PspException e) {
            // Payment stays AUTHORIZED; the merchant can safely retry the capture.
            throw new ApiException(504, "psp_error", e.getMessage());
        }
        Payment updated = applyPspResult(paymentId, r, "api");
        return response(updated);
    }

    // =====================================================================================
    // GET /v1/payments/{id}
    // =====================================================================================

    public Payment get(String paymentId) {
        return db.inTx(c -> payments.find(c, paymentId, false)
                .orElseThrow(() -> new ApiException(404, "payment_not_found", paymentId)));
    }

    // =====================================================================================
    // helpers
    // =====================================================================================

    /** Builds the merchant-facing response and, once the payment is no longer in flight, completes the idempotency record. */
    private ApiResponse response(Connection c, Payment p) throws SQLException {
        ApiResponse resp = response(p);
        if (p.status() != PaymentStatus.CREATED && p.status() != PaymentStatus.PROCESSING) {
            idempotency.completeByPaymentId(c, p.id(), resp.httpStatus(), resp.body());
        }
        return resp;
    }

    private ApiResponse response(Payment p) {
        String body = Json.obj("id", p.id(), "status", p.status().external(), "amount", p.amount(),
                "currency", p.currency(), "failure_code", p.failureCode());
        return new ApiResponse(201, body, p.id());
    }

    private static String eventJson(Payment p) {
        return Json.obj("type", "payment." + p.status().name().toLowerCase(), "payment_id", p.id(),
                "merchant_id", p.merchantId(), "amount", p.amount(), "currency", p.currency());
    }

    private static void validate(CreatePaymentRequest r, String key) {
        if (key == null || key.isBlank()) throw new ApiException(400, "missing_idempotency_key", "Idempotency-Key header required");
        if (r.amount() <= 0) throw new ApiException(400, "invalid_amount", "amount must be a positive integer (minor units)");
        if (r.currency() == null || r.currency().length() != 3) throw new ApiException(400, "invalid_currency", "3-letter ISO code expected");
        if (r.merchantId() == null || r.merchantOrderId() == null || r.paymentToken() == null) {
            throw new ApiException(400, "missing_field", "merchantId, merchantOrderId, paymentToken are required");
        }
    }
}
