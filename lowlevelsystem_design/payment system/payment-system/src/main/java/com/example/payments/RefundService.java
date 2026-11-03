package com.example.payments;

import com.example.payments.PspAdapter.Result.Status;
import java.time.Instant;
import java.util.List;

/**
 * Two phases, like the rest of the system:
 *  1. requestRefund(): validate + record a PENDING refund, under a row lock on the payment so
 *     concurrent requests cannot collectively refund more than was captured.
 *  2. processPending(): a worker calls the PSP (no DB tx open), then books the result + ledger atomically.
 */
public class RefundService {

    private final Database db;
    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final OutboxRepository outbox;
    private final LedgerService ledger;
    private final PspRouter router;

    public RefundService(Database db, PaymentRepository payments, RefundRepository refunds,
                         OutboxRepository outbox, LedgerService ledger, PspRouter router) {
        this.db = db;
        this.payments = payments;
        this.refunds = refunds;
        this.outbox = outbox;
        this.ledger = ledger;
        this.router = router;
    }

    public Refund requestRefund(String paymentId, long amount, String idempotencyKey) {
        if (amount <= 0) throw new ApiException(400, "invalid_amount", "amount must be positive");
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ApiException(400, "missing_idempotency_key", "Idempotency-Key required");
        }
        return db.inTx(c -> {
            Payment p = payments.find(c, paymentId, true)                       // serializes refunds per payment
                    .orElseThrow(() -> new ApiException(404, "payment_not_found", paymentId));

            var existing = refunds.findByPaymentAndKey(c, paymentId, idempotencyKey);
            if (existing.isPresent()) return existing.get();                    // replay

            if (p.status() != PaymentStatus.CAPTURED && p.status() != PaymentStatus.PARTIALLY_REFUNDED) {
                throw new ApiException(422, "invalid_state", "Cannot refund a payment in state " + p.status());
            }
            long committed = refunds.sumActive(c, paymentId);
            if (committed + amount > p.amount()) {
                throw new ApiException(422, "refund_exceeds_captured",
                        "Requested " + amount + " but only " + (p.amount() - committed) + " is refundable");
            }
            Refund r = new Refund(Ids.newId("rf"), paymentId, amount, Refund.Status.PENDING, null,
                    idempotencyKey, Instant.now());
            refunds.insert(c, r);
            outbox.add(c, "refund.events", r.id(),
                    Json.obj("type", "refund.requested", "refund_id", r.id(), "payment_id", paymentId, "amount", amount));
            return r;
        });
    }

    /** @return number of refunds that reached a final state in this run */
    public int processPending(int batchSize) {
        List<Refund> pending = db.inTx(c -> refunds.findPending(c, batchSize));
        int finished = 0;
        for (Refund r : pending) {
            if (processOne(r)) finished++;
        }
        return finished;
    }

    private boolean processOne(Refund r) {
        Payment p = db.inTx(c -> payments.find(c, r.paymentId(), false).orElseThrow());

        PspAdapter.Result result;
        try {
            result = router.byName(p.psp()).refund(p.pspReference(), r.amount(), r.id());   // idempotent on r.id()
        } catch (PspException e) {
            return false;   // stays PENDING, the next run retries with the same refund id
        }

        return db.inTx(c -> {
            Payment locked = payments.find(c, p.id(), true).orElseThrow();
            Refund current = refunds.find(c, r.id()).orElseThrow();
            if (current.status() != Refund.Status.PENDING) return false;        // another worker got there first

            if (result.status() == Status.REFUNDED) {
                refunds.updateStatus(c, r.id(), Refund.Status.SUCCEEDED, result.pspReference());
                boolean full = refunds.sumSucceeded(c, p.id()) == locked.amount();
                ledger.postRefund(c, locked, current, full);
                payments.transition(c, locked, full ? PaymentStatus.REFUNDED : PaymentStatus.PARTIALLY_REFUNDED,
                        "refund_worker", Json.obj("refund_id", r.id(), "amount", r.amount()));
                outbox.add(c, "refund.events", r.id(),
                        Json.obj("type", "refund.succeeded", "refund_id", r.id(), "payment_id", p.id(), "amount", r.amount()));
            } else {
                refunds.updateStatus(c, r.id(), Refund.Status.FAILED, null);   // frees the amount for a new attempt
                outbox.add(c, "refund.events", r.id(),
                        Json.obj("type", "refund.failed", "refund_id", r.id(), "payment_id", p.id()));
            }
            return true;
        });
    }
}
