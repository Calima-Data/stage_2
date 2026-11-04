package com.calima.search.ingestion;

import com.calima.search.common.Sqlite;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * The persistent record of ingestion, owned by this service and shared by its replicas.
 *
 * <p>A book is ready only in state {@code available}, which is written after both files are in
 * place. {@code in_progress} rows carry an owner and a lease: at most one request works on a book,
 * and a request that died is replaced once its lease expires. The path chosen by the first claim
 * is kept across attempts, so a retry in a later hour cannot leave a second copy of the book.
 */
public final class IngestionLedger {

    public static final String AVAILABLE = "available";
    public static final String IN_PROGRESS = "in_progress";
    public static final String FAILED = "failed";

    public record Entry(int bookId, String state, String path, long headerBytes, long bodyBytes, String bodySha256,
                        String lastError) {
    }

    public enum ClaimResult { ACQUIRED, ALREADY_AVAILABLE, HELD_BY_ANOTHER }

    public record Claim(ClaimResult result, Entry entry) {
    }

    private final Sqlite db;
    private final long leaseMs;

    public IngestionLedger(Sqlite db, long leaseMs) throws SQLException {
        this.db = db;
        this.leaseMs = leaseMs;
        db.withConnection(c -> {
            Sqlite.execute(c, "CREATE TABLE IF NOT EXISTS books ("
                    + "book_id INTEGER PRIMARY KEY, state TEXT NOT NULL, path TEXT NOT NULL,"
                    + "owner TEXT, lease_expires_at INTEGER NOT NULL DEFAULT 0,"
                    + "header_bytes INTEGER, body_bytes INTEGER, body_sha256 TEXT,"
                    + "last_error TEXT, attempts INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL)");
            return null;
        });
    }

    /** Atomically decides whether this request may ingest the book. */
    public Claim claim(int bookId, String owner, String candidatePath, long now) throws SQLException {
        return db.write(c -> {
            Entry existing = find(c, bookId);
            String currentOwner = null;
            long leaseExpiry = 0;
            if (existing != null) {
                if (AVAILABLE.equals(existing.state())) {
                    return new Claim(ClaimResult.ALREADY_AVAILABLE, existing);
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT owner, lease_expires_at FROM books WHERE book_id = ?")) {
                    ps.setInt(1, bookId);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        currentOwner = rs.getString(1);
                        leaseExpiry = rs.getLong(2);
                    }
                }
                if (IN_PROGRESS.equals(existing.state()) && currentOwner != null && leaseExpiry > now) {
                    return new Claim(ClaimResult.HELD_BY_ANOTHER, existing);
                }
            }
            String path = existing != null ? existing.path() : candidatePath;
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO books (book_id, state, path, owner, lease_expires_at, attempts, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, 1, ?) "
                            + "ON CONFLICT(book_id) DO UPDATE SET state = excluded.state, owner = excluded.owner, "
                            + "lease_expires_at = excluded.lease_expires_at, attempts = attempts + 1, "
                            + "updated_at = excluded.updated_at, last_error = NULL")) {
                ps.setInt(1, bookId);
                ps.setString(2, IN_PROGRESS);
                ps.setString(3, path);
                ps.setString(4, owner);
                ps.setLong(5, now + leaseMs);
                ps.setLong(6, now);
                ps.executeUpdate();
            }
            return new Claim(ClaimResult.ACQUIRED, find(c, bookId));
        });
    }

    /** Publishes the book as ready. Returns false if this request lost its lease to another one. */
    public boolean complete(int bookId, String owner, BookStore.Stored stored, long now) throws SQLException {
        return db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE books SET state = ?, owner = NULL, lease_expires_at = 0, header_bytes = ?, body_bytes = ?, "
                            + "body_sha256 = ?, updated_at = ? WHERE book_id = ? AND owner = ?")) {
                ps.setString(1, AVAILABLE);
                ps.setLong(2, stored.headerBytes());
                ps.setLong(3, stored.bodyBytes());
                ps.setString(4, stored.bodySha256());
                ps.setLong(5, now);
                ps.setInt(6, bookId);
                ps.setString(7, owner);
                return ps.executeUpdate() == 1;
            }
        });
    }

    /** Releases the claim after a failed attempt; a later request may try again at once. */
    public void fail(int bookId, String owner, String error, long now) throws SQLException {
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE books SET state = ?, owner = NULL, lease_expires_at = 0, last_error = ?, updated_at = ? "
                            + "WHERE book_id = ? AND owner = ?")) {
                ps.setString(1, FAILED);
                ps.setString(2, error);
                ps.setLong(3, now);
                ps.setInt(4, bookId);
                ps.setString(5, owner);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public Entry get(int bookId) throws SQLException {
        return db.withConnection(c -> find(c, bookId));
    }

    public List<Integer> listAvailable() throws SQLException {
        return db.withConnection(c -> {
            List<Integer> ids = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT book_id FROM books WHERE state = ? ORDER BY book_id")) {
                ps.setString(1, AVAILABLE);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ids.add(rs.getInt(1));
                    }
                }
            }
            return ids;
        });
    }

    private static Entry find(java.sql.Connection c, int bookId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT state, path, header_bytes, body_bytes, body_sha256, last_error FROM books WHERE book_id = ?")) {
            ps.setInt(1, bookId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new Entry(bookId, rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4),
                        rs.getString(5), rs.getString(6));
            }
        }
    }
}
