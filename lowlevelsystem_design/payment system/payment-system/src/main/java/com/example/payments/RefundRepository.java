package com.example.payments;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class RefundRepository {

    private static final String COLS = "id, payment_id, amount, status, psp_reference, idempotency_key, created_at";

    public void insert(Connection c, Refund r) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO refunds (" + COLS + ") VALUES (?,?,?,?,?,?,?)")) {
            ps.setString(1, r.id());
            ps.setString(2, r.paymentId());
            ps.setLong(3, r.amount());
            ps.setString(4, r.status().name());
            ps.setString(5, r.pspReference());
            ps.setString(6, r.idempotencyKey());
            ps.setTimestamp(7, Timestamp.from(r.createdAt()));
            ps.executeUpdate();
        }
    }

    public Optional<Refund> find(Connection c, String id) throws SQLException {
        return queryOne(c, "SELECT " + COLS + " FROM refunds WHERE id = ?", id);
    }

    public Optional<Refund> findByPaymentAndKey(Connection c, String paymentId, String key) throws SQLException {
        return queryOne(c, "SELECT " + COLS + " FROM refunds WHERE payment_id = ? AND idempotency_key = ?",
                paymentId, key);
    }

    public List<Refund> findPending(Connection c, int limit) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT " + COLS + " FROM refunds WHERE status = 'PENDING' ORDER BY created_at LIMIT ?")) {
            ps.setInt(1, limit);
            List<Refund> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(map(rs));
            }
            return out;
        }
    }

    /** PENDING + SUCCEEDED: money that is already spoken for. */
    public long sumActive(Connection c, String paymentId) throws SQLException {
        return sum(c, paymentId, "status IN ('PENDING','SUCCEEDED')");
    }

    public long sumSucceeded(Connection c, String paymentId) throws SQLException {
        return sum(c, paymentId, "status = 'SUCCEEDED'");
    }

    public void updateStatus(Connection c, String id, Refund.Status status, String pspReference) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE refunds SET status = ?, psp_reference = COALESCE(CAST(? AS VARCHAR), psp_reference) WHERE id = ?")) {
            ps.setString(1, status.name());
            ps.setString(2, pspReference);
            ps.setString(3, id);
            ps.executeUpdate();
        }
    }

    private long sum(Connection c, String paymentId, String predicate) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COALESCE(SUM(amount), 0) FROM refunds WHERE payment_id = ? AND " + predicate)) {
            ps.setString(1, paymentId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private Optional<Refund> queryOne(Connection c, String sql, String... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setString(i + 1, args[i]);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    private static Refund map(ResultSet rs) throws SQLException {
        return new Refund(rs.getString("id"), rs.getString("payment_id"), rs.getLong("amount"),
                Refund.Status.valueOf(rs.getString("status")), rs.getString("psp_reference"),
                rs.getString("idempotency_key"), rs.getTimestamp("created_at").toInstant());
    }
}
