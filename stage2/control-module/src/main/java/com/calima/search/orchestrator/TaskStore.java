package com.calima.search.orchestrator;

import com.calima.search.common.Sqlite;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The persistent workflow state: one row per book, driven through
 * {@code PENDING -> INGESTED -> INDEXED -> PUBLISHED}, or to {@code FAILED}.
 *
 * <p>Every transition is one transaction guarded by the owner of the claim. A step is recorded only
 * after its service confirmed it, so a crash in between just repeats the step, and the services
 * make every step idempotent.
 */
public final class TaskStore {

    public static final String PENDING = "PENDING";
    public static final String INGESTED = "INGESTED";
    public static final String INDEXED = "INDEXED";
    public static final String PUBLISHED = "PUBLISHED";
    public static final String FAILED = "FAILED";

    public record Task(int bookId, String state, int attempts) {
    }

    /** What a step spent, added to the task for the coordination-cost measurements. */
    public record StepTiming(String column, long serviceMillis, long coordinationNanos) {
    }

    private static final List<String> TIMING_COLUMNS = List.of("ingest_ms", "index_ms", "publish_ms");

    private final Sqlite db;
    private final long leaseMs;

    public TaskStore(Sqlite db, long leaseMs) throws SQLException {
        this.db = db;
        this.leaseMs = leaseMs;
        db.write(c -> {
            Sqlite.execute(c, "CREATE TABLE IF NOT EXISTS tasks ("
                    + "book_id INTEGER PRIMARY KEY, state TEXT NOT NULL, attempts INTEGER NOT NULL DEFAULT 0,"
                    + "total_retries INTEGER NOT NULL DEFAULT 0, next_attempt_at INTEGER NOT NULL DEFAULT 0,"
                    + "owner TEXT, lease_expires_at INTEGER NOT NULL DEFAULT 0, last_error TEXT,"
                    + "queued_at INTEGER NOT NULL, first_claimed_at INTEGER, finished_at INTEGER,"
                    + "ingest_ms INTEGER NOT NULL DEFAULT 0, index_ms INTEGER NOT NULL DEFAULT 0,"
                    + "publish_ms INTEGER NOT NULL DEFAULT 0, coordination_us INTEGER NOT NULL DEFAULT 0)");
            Sqlite.execute(c, "CREATE INDEX IF NOT EXISTS tasks_ready ON tasks (state, next_attempt_at)");
            return null;
        });
    }

    /** Adds books as PENDING; books already known keep their state. Returns how many were new. */
    public int enqueue(List<Integer> bookIds, long now) throws SQLException {
        return db.write(c -> {
            int added = 0;
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT OR IGNORE INTO tasks (book_id, state, queued_at) VALUES (?, ?, ?)")) {
                for (int bookId : bookIds) {
                    ps.setInt(1, bookId);
                    ps.setString(2, PENDING);
                    ps.setLong(3, now);
                    added += ps.executeUpdate();
                }
            }
            return added;
        });
    }

    /**
     * Atomically claims the next ready task, or returns null. Unfinished work comes first: a book
     * closer to being published goes before one that has not started, so resuming after a failure
     * finishes pending books before downloading new ones.
     */
    public Task claimNext(String owner, long now) throws SQLException {
        return db.write(c -> {
            Task task = null;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT book_id, state, attempts FROM tasks "
                            + "WHERE state IN ('INDEXED', 'INGESTED', 'PENDING') AND next_attempt_at <= ? "
                            + "AND lease_expires_at <= ? "
                            + "ORDER BY CASE state WHEN 'INDEXED' THEN 0 WHEN 'INGESTED' THEN 1 ELSE 2 END, "
                            + "next_attempt_at, book_id LIMIT 1")) {
                ps.setLong(1, now);
                ps.setLong(2, now);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        task = new Task(rs.getInt(1), rs.getString(2), rs.getInt(3));
                    }
                }
            }
            if (task == null) {
                return null;
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE tasks SET owner = ?, lease_expires_at = ?, first_claimed_at = COALESCE(first_claimed_at, ?) "
                            + "WHERE book_id = ?")) {
                ps.setString(1, owner);
                ps.setLong(2, now + leaseMs);
                ps.setLong(3, now);
                ps.setInt(4, task.bookId());
                ps.executeUpdate();
            }
            return task;
        });
    }

    /** Records a confirmed step and releases the claim. The attempt counter restarts for the next step. */
    public void advance(int bookId, String owner, String nextState, StepTiming timing, long now) throws SQLException {
        if (!TIMING_COLUMNS.contains(timing.column())) {
            throw new IllegalArgumentException(timing.column());
        }
        long startNanos = System.nanoTime();
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE tasks SET state = ?, attempts = 0, next_attempt_at = 0, owner = NULL, lease_expires_at = 0, "
                            + "last_error = NULL, finished_at = ?, "
                            + timing.column() + " = " + timing.column() + " + ?, "
                            + "coordination_us = coordination_us + ? WHERE book_id = ? AND owner = ?")) {
                ps.setString(1, nextState);
                if (PUBLISHED.equals(nextState)) {
                    ps.setLong(2, now);
                } else {
                    ps.setNull(2, java.sql.Types.INTEGER);
                }
                ps.setLong(3, timing.serviceMillis());
                ps.setLong(4, (timing.coordinationNanos() + System.nanoTime() - startNanos) / 1000);
                ps.setInt(5, bookId);
                ps.setString(6, owner);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Schedules a retry of the current or an earlier state, keeping the failure for diagnosis. */
    public void retry(int bookId, String owner, String state, String error, long nextAttemptAt) throws SQLException {
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE tasks SET state = ?, attempts = attempts + 1, total_retries = total_retries + 1, "
                            + "next_attempt_at = ?, owner = NULL, lease_expires_at = 0, last_error = ? "
                            + "WHERE book_id = ? AND owner = ?")) {
                ps.setString(1, state);
                ps.setLong(2, nextAttemptAt);
                ps.setString(3, error);
                ps.setInt(4, bookId);
                ps.setString(5, owner);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public void fail(int bookId, String owner, String error) throws SQLException {
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE tasks SET state = ?, owner = NULL, lease_expires_at = 0, last_error = ? "
                            + "WHERE book_id = ? AND owner = ?")) {
                ps.setString(1, FAILED);
                ps.setString(2, error);
                ps.setInt(3, bookId);
                ps.setString(4, owner);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Puts a FAILED book back in the queue. Returns false if the book is not in FAILED. */
    public boolean requeueFailed(int bookId) throws SQLException {
        return db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE tasks SET state = ?, attempts = 0, next_attempt_at = 0, last_error = NULL "
                            + "WHERE book_id = ? AND state = ?")) {
                ps.setString(1, PENDING);
                ps.setInt(2, bookId);
                ps.setString(3, FAILED);
                return ps.executeUpdate() == 1;
            }
        });
    }

    /**
     * Frees the claims of a previous run. Only one Control Module runs at a time, so at startup every
     * existing claim belongs to a process that has died; waiting for leases would only delay recovery.
     */
    public int releaseAllClaims() throws SQLException {
        return db.write(c -> {
            try (Statement st = c.createStatement()) {
                return st.executeUpdate("UPDATE tasks SET owner = NULL, lease_expires_at = 0 WHERE owner IS NOT NULL");
            }
        });
    }

    /** One task as seen by operators, or null. */
    public Map<String, Object> describe(int bookId) throws SQLException {
        return db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT state, attempts, total_retries, last_error, next_attempt_at, queued_at, first_claimed_at, "
                            + "finished_at, ingest_ms, index_ms, publish_ms, coordination_us FROM tasks WHERE book_id = ?")) {
                ps.setInt(1, bookId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }
                    Map<String, Object> task = new LinkedHashMap<>();
                    task.put("book_id", bookId);
                    task.put("state", rs.getString("state"));
                    task.put("attempts", rs.getInt("attempts"));
                    task.put("total_retries", rs.getInt("total_retries"));
                    task.put("last_error", rs.getString("last_error"));
                    task.put("ingest_ms", rs.getLong("ingest_ms"));
                    task.put("index_ms", rs.getLong("index_ms"));
                    task.put("publish_ms", rs.getLong("publish_ms"));
                    task.put("coordination_ms", rs.getLong("coordination_us") / 1000.0);
                    return task;
                }
            }
        });
    }

    /** Counts per state and timing means over the published tasks. */
    public Map<String, Object> summary() throws SQLException {
        return db.read(c -> {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("states", countsByState(c));
            summary.put("published_tasks", publishedMetrics(c));
            return summary;
        });
    }

    private static Map<String, Long> countsByState(Connection c) throws SQLException {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String state : List.of(PENDING, INGESTED, INDEXED, PUBLISHED, FAILED)) {
            counts.put(state, 0L);
        }
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT state, COUNT(*) FROM tasks GROUP BY state")) {
            while (rs.next()) {
                counts.put(rs.getString(1), rs.getLong(2));
            }
        }
        return counts;
    }

    private static Map<String, Object> publishedMetrics(Connection c) throws SQLException {
        Map<String, Object> metrics = new LinkedHashMap<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*), AVG(first_claimed_at - queued_at), AVG(ingest_ms), AVG(index_ms), "
                     + "AVG(publish_ms), AVG(coordination_us) / 1000.0, AVG(finished_at - queued_at), "
                     + "MIN(queued_at), MAX(finished_at), SUM(total_retries) FROM tasks WHERE state = 'PUBLISHED'")) {
            rs.next();
            metrics.put("count", rs.getLong(1));
            metrics.put("avg_queue_wait_ms", rs.getDouble(2));
            metrics.put("avg_ingest_ms", rs.getDouble(3));
            metrics.put("avg_index_ms", rs.getDouble(4));
            metrics.put("avg_publish_ms", rs.getDouble(5));
            metrics.put("avg_coordination_ms", rs.getDouble(6));
            metrics.put("avg_total_ms", rs.getDouble(7));
            metrics.put("first_queued_at", rs.getLong(8));
            metrics.put("last_finished_at", rs.getLong(9));
            metrics.put("total_retries", rs.getLong(10));
        }
        return metrics;
    }
}
