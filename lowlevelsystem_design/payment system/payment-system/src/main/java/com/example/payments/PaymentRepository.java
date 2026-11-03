package com.example.payments;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Optional;

public class PaymentRepository {

    private static final String COLS =
            "id, merchant_id, merchant_order_id, amount, currency, payment_token, capture_requested, "
          + "status, psp, psp_reference, failure_code, version, created_at, updated_at";

    public void insert(Connection c, Payment p) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO payments (" + COLS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            int i = 1;
            ps.setString(i++, p.id());
            ps.setString(i++, p.merchantId());
            ps.setString(i++, p.merchantOrderId());
            ps.setLong(i++, p.amount());
            ps.setString(i++, p.currency());
            ps.setString(i++, p.paymentToken());
            ps.setBoolean(i++, p.captureRequested());
            ps.setString(i++, p.status().name());
            ps.setString(i++, p.psp());
            ps.setString(i++, p.pspReference());
            ps.setString(i++, p.failureCode());
            ps.setInt(i++, p.version());
            ps.setTimestamp(i++, Timestamp.from(p.createdAt()));
            ps.setTimestamp(i, Timestamp.from(p.updatedAt()));
            ps.executeUpdate();
        }
    }

    /** forUpdate=true takes a row lock: concurrent workers on the same payment serialize here. */
    public Optional<Payment> find(Connection c, String id, boolean forUpdate) throws SQLException {
        String sql = "SELECT " + COLS + " FROM payments WHERE id = ?" + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    public Optional<Payment> findByPspRef(Connection c, String psp, String pspReference) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT " + COLS + " FROM payments WHERE psp = ? AND psp_reference = ?")) {
            ps.setString(1, psp);
            ps.setString(2, pspReference);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    /** Payments whose outcome we never learned (PSP timeout, crash between steps). */
    public List<Payment> findStuck(Connection c, Instant updatedBeforeOrAt, int limit) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT " + COLS + " FROM payments WHERE status IN ('PROCESSING','UNKNOWN') "
              + "AND updated_at <= ? ORDER BY updated_at LIMIT ?")) {
            ps.setTimestamp(1, Timestamp.from(updatedBeforeOrAt));
            ps.setInt(2, limit);
            List<Payment> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(map(rs));
            }
            return out;
        }
    }

    /**
     * Validated state change with optimistic locking (compare-and-set on version),
     * plus an audit row in payment_events. Returns the fresh row.
     */
    public Payment transition(Connection c, Payment p, PaymentStatus to, String actor, String metadata)
            throws SQLException {
        if (!p.status().canTransitionTo(to)) {
            throw new IllegalStateException("Illegal transition " + p.status() + " -> " + to + " for " + p.id());
        }
        int rows;
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE payments SET status = ?, version = version + 1, updated_at = ? WHERE id = ? AND version = ?")) {
            ps.setString(1, to.name());
            ps.setTimestamp(2, Timestamp.from(Instant.now()));
            ps.setString(3, p.id());
            ps.setInt(4, p.version());
            rows = ps.executeUpdate();
        }
        if (rows == 0) throw new ConcurrentModificationException("Payment " + p.id() + " changed concurrently");
        addEvent(c, p.id(), p.status(), to, actor, metadata);
        return find(c, p.id(), false).orElseThrow();
    }

    /** Sets only the non-null arguments. Call while holding the row lock. */
    public void annotate(Connection c, String id, String psp, String pspReference, String failureCode)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE payments SET psp = COALESCE(CAST(? AS VARCHAR), psp), "
              + "psp_reference = COALESCE(CAST(? AS VARCHAR), psp_reference), "
              + "failure_code = COALESCE(CAST(? AS VARCHAR), failure_code) WHERE id = ?")) {
            ps.setString(1, psp);
            ps.setString(2, pspReference);
            ps.setString(3, failureCode);
            ps.setString(4, id);
            ps.executeUpdate();
        }
    }

    public void addEvent(Connection c, String paymentId, PaymentStatus from, PaymentStatus to,
                         String actor, String metadata) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO payment_events (payment_id, from_status, to_status, actor, metadata, created_at) "
              + "VALUES (?,?,?,?,?,?)")) {
            ps.setString(1, paymentId);
            ps.setString(2, from == null ? null : from.name());
            ps.setString(3, to.name());
            ps.setString(4, actor);
            ps.setString(5, metadata);
            ps.setTimestamp(6, Timestamp.from(Instant.now()));
            ps.executeUpdate();
        }
    }

    public List<String> history(Connection c, String paymentId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT from_status, to_status, actor FROM payment_events WHERE payment_id = ? ORDER BY id")) {
            ps.setString(1, paymentId);
            List<String> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(String.valueOf(rs.getString(1)) + " -> " + rs.getString(2) + " (" + rs.getString(3) + ")");
                }
            }
            return out;
        }
    }

    private static Payment map(ResultSet rs) throws SQLException {
        return new Payment(
                rs.getString("id"),
                rs.getString("merchant_id"),
                rs.getString("merchant_order_id"),
                rs.getLong("amount"),
                rs.getString("currency").trim(),
                rs.getString("payment_token"),
                rs.getBoolean("capture_requested"),
                PaymentStatus.valueOf(rs.getString("status")),
                rs.getString("psp"),
                rs.getString("psp_reference"),
                rs.getString("failure_code"),
                rs.getInt("version"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}
