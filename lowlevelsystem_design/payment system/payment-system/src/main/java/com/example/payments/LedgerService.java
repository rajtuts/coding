package com.example.payments;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Append-only double-entry ledger. Every posting is a transaction whose debits equal its credits.
 * Transaction ids are derived from the business event (CAPTURE:{paymentId}, REFUND:{refundId}),
 * so posting the same event twice is impossible.
 */
public class LedgerService {

    public static final int PLATFORM_FEE_BPS = 200;   // 2.00%
    public static final String FEE_ACCOUNT = "platform:fee_revenue";

    private record Line(String account, char direction, long amount) { }

    private final Database db;

    public LedgerService(Database db) { this.db = db; }

    public static long fee(long amount) { return amount * PLATFORM_FEE_BPS / 10_000; }

    // ---------- account management (done at onboarding, so postings never race on account creation) ----------

    public void openPlatformAccounts(String currency) {
        db.execute(c -> {
            upsertAccount(c, FEE_ACCOUNT, "REVENUE", currency);
            upsertAccount(c, "platform:bank_cash", "ASSET", currency);
        });
    }

    public void openMerchantAccount(String merchantId, String currency) {
        db.execute(c -> upsertAccount(c, "merchant:" + merchantId + ":payable", "LIABILITY", currency));
    }

    public void openPspAccount(String psp, String currency) {
        db.execute(c -> upsertAccount(c, "psp:" + psp + ":receivable", "ASSET", currency));
    }

    private void upsertAccount(Connection c, String id, String type, String currency) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "MERGE INTO accounts (id, type, currency) KEY (id) VALUES (?,?,?)")) {
            ps.setString(1, id);
            ps.setString(2, type);
            ps.setString(3, currency);
            ps.executeUpdate();
        }
    }

    // ---------- postings (always called inside the caller's transaction) ----------

    /** D psp receivable (gross) / C merchant payable (net) / C platform fee. */
    public void postCapture(Connection c, Payment p) throws SQLException {
        long fee = fee(p.amount());
        post(c, "CAPTURE:" + p.id(), p.id(), "CAPTURE", p.currency(), List.of(
                new Line("psp:" + p.psp() + ":receivable", 'D', p.amount()),
                new Line("merchant:" + p.merchantId() + ":payable", 'C', p.amount() - fee),
                new Line(FEE_ACCOUNT, 'C', fee)));
    }

    /**
     * Mirror image of the capture, proportional to the refunded amount. The final refund takes
     * whatever fee is left, so rounding never leaves orphan paise on the books.
     */
    public void postRefund(Connection c, Payment p, Refund r, boolean fullyRefundedAfter) throws SQLException {
        long totalFee = fee(p.amount());
        long feeBack = fullyRefundedAfter
                ? totalFee - feeAlreadyReversed(c, p.id())
                : totalFee * r.amount() / p.amount();
        post(c, "REFUND:" + r.id(), p.id(), "REFUND", p.currency(), List.of(
                new Line("merchant:" + p.merchantId() + ":payable", 'D', r.amount() - feeBack),
                new Line(FEE_ACCOUNT, 'D', feeBack),
                new Line("psp:" + p.psp() + ":receivable", 'C', r.amount())));
    }

    private long feeAlreadyReversed(Connection c, String paymentId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COALESCE(SUM(e.amount), 0) FROM ledger_entries e "
              + "JOIN ledger_transactions t ON t.id = e.txn_id "
              + "WHERE t.payment_id = ? AND t.kind = 'REFUND' AND e.account_id = ? AND e.direction = 'D'")) {
            ps.setString(1, paymentId);
            ps.setString(2, FEE_ACCOUNT);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private void post(Connection c, String txnId, String paymentId, String kind, String currency, List<Line> lines)
            throws SQLException {
        List<Line> nonZero = lines.stream().filter(l -> l.amount() > 0).toList();
        long debits = nonZero.stream().filter(l -> l.direction() == 'D').mapToLong(Line::amount).sum();
        long credits = nonZero.stream().filter(l -> l.direction() == 'C').mapToLong(Line::amount).sum();
        if (debits != credits) {
            throw new IllegalStateException("Unbalanced ledger transaction " + txnId + ": D=" + debits + " C=" + credits);
        }
        if (transactionExists(c, txnId)) return;   // idempotent (caller also holds the payment row lock)

        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO ledger_transactions (id, payment_id, kind, created_at) VALUES (?,?,?,?)")) {
            ps.setString(1, txnId);
            ps.setString(2, paymentId);
            ps.setString(3, kind);
            ps.setTimestamp(4, Timestamp.from(Instant.now()));
            ps.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO ledger_entries (txn_id, account_id, direction, amount, currency) VALUES (?,?,?,?,?)")) {
            for (Line l : nonZero) {
                ps.setString(1, txnId);
                ps.setString(2, l.account());
                ps.setString(3, String.valueOf(l.direction()));
                ps.setLong(4, l.amount());
                ps.setString(5, currency);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private boolean transactionExists(Connection c, String txnId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM ledger_transactions WHERE id = ?")) {
            ps.setString(1, txnId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    // ---------- read side / integrity checks ----------

    /** Human-readable entries of one ledger transaction. */
    public List<String> entries(String txnId) {
        return db.inTx(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT account_id, direction, amount FROM ledger_entries WHERE txn_id = ? ORDER BY id")) {
                ps.setString(1, txnId);
                List<String> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(String.format("%s  %-34s %8d", rs.getString(2), rs.getString(1), rs.getLong(3)));
                    }
                }
                return out;
            }
        });
    }

    public long entryCountForPayment(String paymentId) {
        return db.inTx(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*) FROM ledger_entries e JOIN ledger_transactions t ON t.id = e.txn_id "
                  + "WHERE t.payment_id = ?")) {
                ps.setString(1, paymentId);
                try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getLong(1); }
            }
        });
    }

    /** Debit-minus-credit per account. */
    public Map<String, Long> balances() {
        return db.inTx(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT account_id, SUM(CASE WHEN direction = 'D' THEN amount ELSE -amount END) "
                  + "FROM ledger_entries GROUP BY account_id ORDER BY account_id");
                 ResultSet rs = ps.executeQuery()) {
                Map<String, Long> out = new LinkedHashMap<>();
                while (rs.next()) out.put(rs.getString(1), rs.getLong(2));
                return out;
            }
        });
    }

    /** Ids of ledger transactions whose debits != credits. Must always be empty. */
    public List<String> findUnbalancedTransactions() {
        return db.inTx(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT txn_id FROM ledger_entries GROUP BY txn_id "
                  + "HAVING SUM(CASE WHEN direction = 'D' THEN amount ELSE -amount END) <> 0");
                 ResultSet rs = ps.executeQuery()) {
                List<String> out = new ArrayList<>();
                while (rs.next()) out.add(rs.getString(1));
                return out;
            }
        });
    }
}
