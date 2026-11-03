package com.example.payments;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class OutboxRepository {

    public record Message(long id, String topic, String key, String payload) { }

    /** Call inside the same transaction as the state change it describes. */
    public void add(Connection c, String topic, String key, String payload) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO outbox (topic, msg_key, payload, published, created_at) VALUES (?,?,?,FALSE,?)")) {
            ps.setString(1, topic);
            ps.setString(2, key);
            ps.setString(3, payload);
            ps.setTimestamp(4, Timestamp.from(Instant.now()));
            ps.executeUpdate();
        }
    }

    public List<Message> fetchUnpublished(Connection c, int limit) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, topic, msg_key, payload FROM outbox WHERE published = FALSE ORDER BY id LIMIT ?")) {
            ps.setInt(1, limit);
            List<Message> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Message(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)));
                }
            }
            return out;
        }
    }

    public void markPublished(Connection c, List<Long> ids) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE outbox SET published = TRUE WHERE id = ?")) {
            for (long id : ids) {
                ps.setLong(1, id);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }
}
