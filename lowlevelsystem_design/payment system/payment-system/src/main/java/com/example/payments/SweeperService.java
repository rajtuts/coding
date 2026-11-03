package com.example.payments;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Resolves payments stuck in PROCESSING/UNKNOWN (PSP timeout, crash between steps, lost webhook)
 * by asking the PSP what happened. Run it on a schedule (e.g. every 30s) from a single leader or
 * with row-level SKIP LOCKED on Postgres.
 */
public class SweeperService {

    public record Report(int checked, int resolved, int failedAsNotFound, int stillPending) { }

    private final Database db;
    private final PaymentRepository payments;
    private final PaymentService paymentService;
    private final PspRouter router;

    public SweeperService(Database db, PaymentRepository payments, PaymentService paymentService, PspRouter router) {
        this.db = db;
        this.payments = payments;
        this.paymentService = paymentService;
        this.router = router;
    }

    /**
     * @param minAge             ignore payments touched more recently than this (they are probably still in flight)
     * @param failNotFoundAfter  only treat "PSP has no record" as a failure after this long, since
     *                           real PSP lookups can lag behind
     */
    public Report run(Duration minAge, Duration failNotFoundAfter) {
        Instant now = Instant.now();
        List<Payment> stuck = db.inTx(c -> payments.findStuck(c, now.minus(minAge), 200));

        int resolved = 0, failed = 0, pending = 0;
        for (Payment p : stuck) {
            if (p.psp() == null) { pending++; continue; }

            PspAdapter.Result r;
            try {
                r = router.byName(p.psp()).getStatus(p.id());
            } catch (PspException e) {
                pending++;
                continue;
            }

            if (r.status() == PspAdapter.Result.Status.NOT_FOUND) {
                if (Duration.between(p.createdAt(), now).compareTo(failNotFoundAfter) >= 0) {
                    paymentService.applyPspResult(p.id(),
                            new PspAdapter.Result(PspAdapter.Result.Status.FAILED, null, "PSP_NOT_FOUND"), "sweeper");
                    failed++;
                } else {
                    pending++;
                }
            } else {
                Payment after = paymentService.applyPspResult(p.id(), r, "sweeper");
                if (after.status() != p.status()) resolved++; else pending++;
            }
        }
        return new Report(stuck.size(), resolved, failed, pending);
    }
}
