package com.example.payments;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

public class IdempotencyRepository {

    public record Record(String requestHash, String status, Integer responseCode, String responseBody, String paymentId) { }

    /** Throws a unique-violation SQLException if (merchant, key) already exists. */
    public void insert(Connection c, String merchantId, String key, String requestHash, String paymentId)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO idempotency_keys (merchant_id, idem_key, request_hash, status, payment_id, created_at) "
              + "VALUES (?,?,?,'IN_PROGRESS',?,?)")) {
            ps.setString(1, merchantId);
            ps.setString(2, key);
            ps.setString(3, requestHash);
            ps.setString(4, paymentId);
            ps.setTimestamp(5, Timestamp.from(Instant.now()));
            ps.executeUpdate();
        }
    }

    public Optional<Record> find(Connection c, String merchantId, String key) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT request_hash, status, response_code, response_body, payment_id "
              + "FROM idempotency_keys WHERE merchant_id = ? AND idem_key = ?")) {
            ps.setString(1, merchantId);
            ps.setString(2, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                int code = rs.getInt("response_code");
                Integer responseCode = rs.wasNull() ? null : code;
                return Optional.of(new Record(rs.getString("request_hash"), rs.getString("status"),
                        responseCode, rs.getString("response_body"), rs.getString("payment_id")));
            }
        }
    }

    /** No-op if the key is already COMPLETED, so it is safe to call from every settlement path. */
    public int completeByPaymentId(Connection c, String paymentId, int code, String body) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE idempotency_keys SET status = 'COMPLETED', response_code = ?, response_body = ? "
              + "WHERE payment_id = ? AND status = 'IN_PROGRESS'")) {
            ps.setInt(1, code);
            ps.setString(2, body);
            ps.setString(3, paymentId);
            return ps.executeUpdate();
        }
    }
}
