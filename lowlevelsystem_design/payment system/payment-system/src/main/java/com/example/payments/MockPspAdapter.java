package com.example.payments;

import com.example.payments.PspAdapter.Result.Status;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory PSP whose behaviour is driven by the payment token:
 *   pm_ok            approve
 *   pm_declined      CARD_DECLINED
 *   pm_timeout       PSP processes the payment, but the response is lost (read timeout)
 *   pm_timeout_lost  PSP never sees the request (timeout before it arrived)
 * Like real PSPs it is idempotent on our reference.
 */
public class MockPspAdapter implements PspAdapter {

    private final String name;
    private final Map<String, Result> byReference = new ConcurrentHashMap<>();
    private final Map<String, Result> refundsById = new ConcurrentHashMap<>();
    private volatile boolean down;

    public MockPspAdapter(String name) { this.name = name; }

    public void setDown(boolean down) { this.down = down; }

    @Override public String name() { return name; }

    @Override
    public Result authorize(AuthRequest req) {
        if (down) throw new PspException(name + ": connection refused", false);

        Result existing = byReference.get(req.reference());
        if (existing != null) return existing;                       // idempotent replay

        String pspRef = name + "_" + UUID.randomUUID().toString().substring(0, 8);
        Status ok = req.capture() ? Status.CAPTURED : Status.AUTHORIZED;

        switch (req.token()) {
            case "pm_declined" -> {
                Result r = new Result(Status.FAILED, pspRef, "CARD_DECLINED");
                byReference.put(req.reference(), r);
                return r;
            }
            case "pm_timeout" -> {
                byReference.put(req.reference(), new Result(ok, pspRef, null));   // processed...
                throw new PspException(name + ": read timeout", true);            // ...but we never hear back
            }
            case "pm_timeout_lost" -> throw new PspException(name + ": read timeout", true);   // never processed
            default -> {
                Result r = new Result(ok, pspRef, null);
                byReference.put(req.reference(), r);
                return r;
            }
        }
    }

    @Override
    public Result capture(String pspReference, long amount, String captureRef) {
        if (down) throw new PspException(name + ": connection refused", false);
        for (Map.Entry<String, Result> e : byReference.entrySet()) {
            if (pspReference.equals(e.getValue().pspReference())) {
                Result captured = new Result(Status.CAPTURED, pspReference, null);
                e.setValue(captured);
                return captured;
            }
        }
        return new Result(Status.FAILED, pspReference, "UNKNOWN_REFERENCE");
    }

    @Override
    public Result refund(String pspReference, long amount, String refundId) {
        if (down) throw new PspException(name + ": connection refused", false);
        return refundsById.computeIfAbsent(refundId,
                id -> new Result(Status.REFUNDED, name + "_rf_" + UUID.randomUUID().toString().substring(0, 8), null));
    }

    @Override
    public Result getStatus(String reference) {
        if (down) throw new PspException(name + ": connection refused", false);
        return byReference.getOrDefault(reference, new Result(Status.NOT_FOUND, null, null));
    }
}
