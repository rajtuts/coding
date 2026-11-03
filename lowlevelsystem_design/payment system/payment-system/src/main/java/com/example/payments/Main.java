package com.example.payments;

import com.example.payments.PaymentService.ApiResponse;
import com.example.payments.PaymentService.CreatePaymentRequest;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Wires the system together and walks through the interesting scenarios. Run: mvn -q compile exec:java */
public class Main {

    private static final String MERCHANT = "m_acme";

    private static Database db;
    private static PaymentRepository payments;
    private static PaymentService paymentService;
    private static RefundService refundService;
    private static WebhookService webhooks;
    private static SweeperService sweeper;
    private static LedgerService ledger;
    private static PspRouter router;
    private static MockPspAdapter alpha;
    private static OutboxRelay relay;
    private static InMemoryBroker broker;

    public static void main(String[] args) throws Exception {
        setup();

        // ------------------------------------------------------------------------------------------
        section("1. Happy path: card payment with immediate capture (Rs 499.00 = 49900 paise)");
        ApiResponse happy = pay("ord_1", 49_900, "pm_ok", true, "idem-1");
        String happyId = happy.paymentId();
        System.out.println("response      : " + happy.httpStatus() + " " + happy.body());
        System.out.println("state history : " + history(happyId));
        System.out.println("ledger (2% platform fee):");
        ledger.entries("CAPTURE:" + happyId).forEach(e -> System.out.println("    " + e));

        // ------------------------------------------------------------------------------------------
        section("2. Idempotency: the client retries the same request");
        ApiResponse replay = pay("ord_1", 49_900, "pm_ok", true, "idem-1");
        System.out.println("identical response replayed : " + replay.body().equals(happy.body()));
        System.out.println("payment rows for ord_1      : " + count("SELECT COUNT(*) FROM payments WHERE merchant_order_id = ?", "ord_1"));
        try {
            pay("ord_1", 99_900, "pm_ok", true, "idem-1");
        } catch (ApiException e) {
            System.out.println("same key, different payload : " + e);
        }
        try {
            pay("ord_1", 49_900, "pm_ok", true, "a-brand-new-key");
        } catch (ApiException e) {
            System.out.println("same order, new key         : " + e);
        }

        // ------------------------------------------------------------------------------------------
        section("3. Six concurrent requests with the SAME Idempotency-Key");
        int n = 6;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Callable<String> task = () -> {
                start.await();
                try {
                    return "OK   " + pay("ord_conc", 25_000, "pm_ok", true, "idem-conc").paymentId();
                } catch (ApiException e) {
                    return "FAIL " + e;
                } catch (RuntimeException e) {
                    return "ERR  " + e;
                }
            };
            futures.add(pool.submit(task));
        }
        start.countDown();
        Set<String> ids = new TreeSet<>();
        for (Future<String> f : futures) {
            String s = f.get();
            System.out.println("  " + s);
            if (s.startsWith("OK")) ids.add(s.substring(5).trim());
        }
        pool.shutdown();
        System.out.println("distinct payment ids : " + ids.size());
        System.out.println("payment rows         : " + count("SELECT COUNT(*) FROM payments WHERE merchant_order_id = ?", "ord_conc"));
        System.out.println("capture ledger txns  : " + count(
                "SELECT COUNT(*) FROM ledger_transactions WHERE kind = 'CAPTURE' AND payment_id IN "
              + "(SELECT id FROM payments WHERE merchant_order_id = ?)", "ord_conc"));

        // ------------------------------------------------------------------------------------------
        section("4. Declines: PSP decline and risk decline never touch the ledger");
        ApiResponse declined = pay("ord_decl", 10_000, "pm_declined", true, "idem-decl");
        System.out.println("PSP decline  : " + declined.body() + "  ledger entries=" + ledger.entryCountForPayment(declined.paymentId()));
        ApiResponse risky = pay("ord_risk", 6_000_000, "pm_ok", true, "idem-risk");
        System.out.println("risk decline : " + risky.body() + "  ledger entries=" + ledger.entryCountForPayment(risky.paymentId()));

        // ------------------------------------------------------------------------------------------
        section("5. Authorize now, capture later");
        ApiResponse auth = pay("ord_auth", 120_000, "pm_ok", false, "idem-auth");
        System.out.println("after authorize : " + auth.body() + "  ledger entries=" + ledger.entryCountForPayment(auth.paymentId()));
        ApiResponse captured = paymentService.capture(auth.paymentId(), 120_000);
        System.out.println("after capture   : " + captured.body() + "  ledger entries=" + ledger.entryCountForPayment(auth.paymentId()));
        try {
            paymentService.capture(auth.paymentId(), 120_000);
        } catch (ApiException e) {
            System.out.println("capture again   : " + e);
        }

        // ------------------------------------------------------------------------------------------
        section("6. PSP timeouts: outcome unknown -> UNKNOWN -> sweeper asks the PSP");
        ApiResponse t1 = pay("ord_t1", 30_000, "pm_timeout", true, "idem-t1");        // PSP charged, response lost
        ApiResponse t2 = pay("ord_t2", 30_000, "pm_timeout_lost", true, "idem-t2");   // PSP never saw it
        System.out.println("merchant sees t1 : " + t1.body());
        System.out.println("internal state t1: " + paymentService.get(t1.paymentId()).status() + ", t2: " + paymentService.get(t2.paymentId()).status());
        SweeperService.Report report = sweeper.run(Duration.ZERO, Duration.ZERO);
        System.out.println("sweeper          : " + report);
        System.out.println("t1 (PSP charged) : " + paymentService.get(t1.paymentId()).status()
                + ", ledger entries=" + ledger.entryCountForPayment(t1.paymentId()));
        System.out.println("t2 (never seen)  : " + paymentService.get(t2.paymentId()).status()
                + ", failure=" + paymentService.get(t2.paymentId()).failureCode());
        pay("ord_ok2", 5_000, "pm_ok", true, "idem-ok2");   // a healthy payment in between (resets the circuit counter)

        // ------------------------------------------------------------------------------------------
        section("7. Lost response resolved by webhook (duplicate delivery + forged signature)");
        ApiResponse t3 = pay("ord_t3", 70_000, "pm_timeout", true, "idem-t3");
        String pspRef = alpha.getStatus(t3.paymentId()).pspReference();
        String body = "evt_1001|payment.captured|" + t3.paymentId() + "|" + pspRef;
        System.out.println("before webhook  : " + paymentService.get(t3.paymentId()).status());
        System.out.println("webhook #1      : " + webhooks.handle("alpha", webhooks.sign(body), body));
        System.out.println("webhook #2 (dup): " + webhooks.handle("alpha", webhooks.sign(body), body));
        try {
            webhooks.handle("alpha", "deadbeef", body);
        } catch (ApiException e) {
            System.out.println("forged webhook  : " + e);
        }
        System.out.println("after webhook   : " + paymentService.get(t3.paymentId()).status()
                + ", capture ledger txns=" + count("SELECT COUNT(*) FROM ledger_transactions WHERE id = ?", "CAPTURE:" + t3.paymentId()));
        String stale = "evt_1002|payment.authorized|" + t3.paymentId() + "|" + pspRef;
        System.out.println("stale 'authorized' event after capture -> " + webhooks.handle("alpha", webhooks.sign(stale), stale)
                + ", state still " + paymentService.get(t3.paymentId()).status());

        // ------------------------------------------------------------------------------------------
        section("8. PSP outage: circuit breaker opens, new traffic fails over to the next PSP");
        alpha.setDown(true);
        for (int i = 1; i <= 4; i++) {
            ApiResponse r = pay("ord_out" + i, 10_000, "pm_ok", true, "idem-out" + i);
            Payment p = paymentService.get(r.paymentId());
            System.out.printf("  payment %d -> %-9s psp=%-5s failure=%-16s alpha circuit open=%s%n",
                    i, p.status(), p.psp(), p.failureCode(), router.breaker("alpha").isOpen());
        }
        alpha.setDown(false);

        // ------------------------------------------------------------------------------------------
        section("9. Refunds: idempotent requests, no over-refunding under concurrency, proportional fee reversal");
        Refund first = refundService.requestRefund(happyId, 10_000, "rf-key-1");
        Refund firstAgain = refundService.requestRefund(happyId, 10_000, "rf-key-1");
        System.out.println("replayed request returns same refund: " + first.id().equals(firstAgain.id()));
        System.out.println("processed: " + refundService.processPending(10) + " -> payment is " + paymentService.get(happyId).status());

        ExecutorService pool2 = Executors.newFixedThreadPool(4);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> refundFutures = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            final String key = "rf-conc-" + i;
            Callable<String> task = () -> {
                go.await();
                try {
                    return "accepted " + refundService.requestRefund(happyId, 15_000, key).id();
                } catch (ApiException e) {
                    return "rejected " + e.code();
                }
            };
            refundFutures.add(pool2.submit(task));
        }
        go.countDown();
        System.out.println("4 concurrent refunds of 15000 against 39900 refundable:");
        for (Future<String> f : refundFutures) System.out.println("  " + f.get());
        pool2.shutdown();
        System.out.println("processed: " + refundService.processPending(10) + " -> payment is " + paymentService.get(happyId).status());

        Refund last = refundService.requestRefund(happyId, 9_900, "rf-final");
        refundService.processPending(10);
        System.out.println("final refund of the remaining 9900 -> payment is " + paymentService.get(happyId).status());
        System.out.println("fee reversal on the final refund takes the remaining fee exactly:");
        ledger.entries("REFUND:" + last.id()).forEach(e -> System.out.println("    " + e));
        try {
            refundService.requestRefund(happyId, 1, "rf-too-much");
        } catch (ApiException e) {
            System.out.println("refund of a fully refunded payment : " + e);
        }
        System.out.println("full history: " + history(happyId));

        // ------------------------------------------------------------------------------------------
        section("10. Transactional outbox -> broker (at-least-once) -> idempotent consumer");
        System.out.println("unpublished outbox rows: " + count("SELECT COUNT(*) FROM outbox WHERE published = FALSE"));
        relay.pollOnce(1000, true);    // publishes, then 'crashes' before marking rows
        relay.pollOnce(1000);          // restart: everything is delivered again
        System.out.println("messages delivered to broker : " + broker.deliveredCount() + " (duplicates after the crash)");
        System.out.println("messages processed by consumer: " + broker.processedCount() + " (deduplicated by message id)");
        System.out.println("unpublished outbox rows now  : " + count("SELECT COUNT(*) FROM outbox WHERE published = FALSE"));

        // ------------------------------------------------------------------------------------------
        section("11. Integrity checks");
        System.out.println("unbalanced ledger transactions: " + ledger.findUnbalancedTransactions());
        long total = 0;
        System.out.println("account balances (debit - credit):");
        for (Map.Entry<String, Long> e : ledger.balances().entrySet()) {
            System.out.printf("    %-34s %10d%n", e.getKey(), e.getValue());
            total += e.getValue();
        }
        System.out.println("trial balance (must be 0)     : " + total);
        System.out.println("payments by status:");
        printRows("SELECT status, COUNT(*) FROM payments GROUP BY status ORDER BY status");
    }

    // ==============================================================================================
    // wiring
    // ==============================================================================================

    private static void setup() {
        db = Database.inMemory();
        db.initSchema();

        alpha = new MockPspAdapter("alpha");
        MockPspAdapter beta = new MockPspAdapter("beta");
        router = new PspRouter(List.of(alpha, beta), 3, Duration.ofSeconds(30));

        payments = new PaymentRepository();
        IdempotencyRepository idempotency = new IdempotencyRepository();
        RefundRepository refundRepository = new RefundRepository();
        OutboxRepository outbox = new OutboxRepository();
        ledger = new LedgerService(db);

        paymentService = new PaymentService(db, payments, idempotency, outbox, ledger, new RiskService(), router);
        refundService = new RefundService(db, payments, refundRepository, outbox, ledger, router);
        webhooks = new WebhookService(db, payments, paymentService, "whsec_demo_secret");
        sweeper = new SweeperService(db, payments, paymentService, router);
        broker = new InMemoryBroker();
        relay = new OutboxRelay(db, outbox, broker);

        // Onboarding creates ledger accounts up front, so postings never race on account creation.
        ledger.openPlatformAccounts("INR");
        ledger.openMerchantAccount(MERCHANT, "INR");
        ledger.openPspAccount("alpha", "INR");
        ledger.openPspAccount("beta", "INR");
    }

    // ==============================================================================================
    // small helpers
    // ==============================================================================================

    private static ApiResponse pay(String order, long amount, String token, boolean capture, String idemKey) {
        return paymentService.createPayment(
                new CreatePaymentRequest(MERCHANT, order, amount, "INR", token, capture), idemKey);
    }

    private static List<String> history(String paymentId) {
        return db.inTx(c -> payments.history(c, paymentId));
    }

    private static long count(String sql, String... args) {
        return db.inTx(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) ps.setString(i + 1, args[i]);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
    }

    private static void printRows(String sql) {
        db.execute(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) System.out.printf("    %-20s %s%n", rs.getString(1), rs.getString(2));
            }
        });
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("=== " + title + " ===");
    }
}
