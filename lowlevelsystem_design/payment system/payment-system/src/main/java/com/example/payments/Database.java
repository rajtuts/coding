package com.example.payments;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Thin JDBC helper: one connection per transaction, commit on success, rollback on any failure.
 * Repositories receive the Connection so several of them can join the same transaction.
 */
public final class Database {

    @FunctionalInterface
    public interface TxWork<T> { T run(Connection c) throws SQLException; }

    @FunctionalInterface
    public interface TxRunnable { void run(Connection c) throws SQLException; }

    public static class DataAccessException extends RuntimeException {
        private final SQLException cause;
        public DataAccessException(SQLException cause) { super(cause.getMessage(), cause); this.cause = cause; }
        public boolean isUniqueViolation() { return Database.isUniqueViolation(cause); }
        public boolean isConcurrencyConflict() { return Database.isConcurrencyConflict(cause); }
    }

    private final String url;

    public Database(String url) { this.url = url; }

    /** Shared in-memory H2 database in PostgreSQL mode, survives across connections in this JVM. */
    public static Database inMemory() {
        return new Database("jdbc:h2:mem:payments;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
    }

    public Connection connection() throws SQLException {
        return DriverManager.getConnection(url, "sa", "");
    }

    public void initSchema() {
        String script;
        try (InputStream in = Database.class.getResourceAsStream("/schema.sql")) {
            if (in == null) throw new IllegalStateException("schema.sql not found on classpath");
            script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        StringBuilder cleaned = new StringBuilder();
        for (String line : script.split("\n")) {
            if (!line.trim().startsWith("--")) cleaned.append(line).append('\n');
        }
        execute(c -> {
            for (String stmt : cleaned.toString().split(";")) {
                if (stmt.isBlank()) continue;
                try (Statement st = c.createStatement()) { st.execute(stmt); }
            }
        });
    }

    public <T> T inTx(TxWork<T> work) {
        try (Connection c = connection()) {
            c.setAutoCommit(false);
            try {
                T result = work.run(c);
                c.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                try { c.rollback(); } catch (SQLException ignored) { }
                throw e;
            }
        } catch (SQLException e) {
            throw new DataAccessException(e);
        }
    }

    public void execute(TxRunnable work) {
        inTx(c -> { work.run(c); return null; });
    }

    // ---- SQLSTATE helpers (H2 error codes mirror these) ----

    public static boolean isUniqueViolation(SQLException e) {
        return "23505".equals(e.getSQLState());
    }

    /** Lock timeout, concurrent update, deadlock: caller should treat as "someone else is working on it". */
    public static boolean isConcurrencyConflict(SQLException e) {
        int code = e.getErrorCode();
        return code == 50200 || code == 90131 || code == 40001 || "40001".equals(e.getSQLState());
    }
}
