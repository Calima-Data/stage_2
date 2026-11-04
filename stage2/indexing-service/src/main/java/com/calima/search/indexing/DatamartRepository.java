package com.calima.search.indexing;

import com.calima.search.common.Faults;
import com.calima.search.common.Sqlite;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.TreeMap;

/**
 * The datamart: book metadata and the inverted index, in one SQLite file owned by this service.
 *
 * <p><b>Publication.</b> Metadata, postings and the version counter change in one transaction, so
 * its commit is the publication of the update. Search reads inside a snapshot and therefore sees a
 * book completely or not at all.
 */
public final class DatamartRepository {

    public record BookIndex(int bookId, MetadataExtractor.Metadata metadata, Map<String, Integer> frequencies,
                            String contentSha256) {
        int wordCount() {
            return frequencies.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    public record Status(long booksIndexed, long version, Long lastUpdateMillis, long sizeBytes) {
    }

    private final Sqlite db;

    public DatamartRepository(Sqlite db) throws SQLException {
        this.db = db;
        db.write(c -> {
            Sqlite.execute(c, "CREATE TABLE IF NOT EXISTS books ("
                    + "book_id INTEGER PRIMARY KEY, title TEXT, author TEXT, author_norm TEXT, language TEXT NOT NULL,"
                    + "year INTEGER, word_count INTEGER NOT NULL, unique_terms INTEGER NOT NULL,"
                    + "content_sha256 TEXT NOT NULL, indexed_at INTEGER NOT NULL)");
            // WITHOUT ROWID stores postings clustered by term, which is how search reads them.
            Sqlite.execute(c, "CREATE TABLE IF NOT EXISTS postings ("
                    + "term TEXT NOT NULL, book_id INTEGER NOT NULL, tf INTEGER NOT NULL,"
                    + "PRIMARY KEY (term, book_id)) WITHOUT ROWID");
            Sqlite.execute(c, "CREATE TABLE IF NOT EXISTS index_state ("
                    + "id INTEGER PRIMARY KEY CHECK (id = 1), version INTEGER NOT NULL, last_update INTEGER)");
            Sqlite.execute(c, "INSERT OR IGNORE INTO index_state (id, version, last_update) VALUES (1, 0, NULL)");
            return null;
        });
    }

    /**
     * Publishes one book atomically. Returns false, changing nothing, when the same content is
     * already indexed: this is what makes a repeated update idempotent, also across replicas.
     */
    public boolean publish(BookIndex book) throws SQLException {
        return db.write(c -> {
            String current = currentSha(c, book.bookId());
            if (book.contentSha256().equals(current)) {
                return false;
            }
            if (current != null) {
                try (PreparedStatement delete = c.prepareStatement("DELETE FROM postings WHERE book_id = ?")) {
                    delete.setInt(1, book.bookId());
                    delete.executeUpdate();
                }
            }
            insertBook(c, book);
            Faults.point("index_before_commit");
            bumpVersion(c);
            return true;
        });
    }

    /** A source of books for a rebuild; it runs while this service holds the write transaction. */
    @FunctionalInterface
    public interface BookFeed {
        void feed(BookSink sink) throws Exception;
    }

    @FunctionalInterface
    public interface BookSink {
        void accept(BookIndex book) throws SQLException;
    }

    /**
     * Replaces the whole datamart in one transaction. Readers keep the previous snapshot until the
     * commit, and any failure rolls back to it. Other writers wait for the lock meanwhile.
     */
    public void rebuild(BookFeed feed) throws SQLException {
        db.write(c -> {
            Sqlite.execute(c, "DELETE FROM postings");
            Sqlite.execute(c, "DELETE FROM books");
            try {
                feed.feed(book -> insertBook(c, book));
            } catch (SQLException | RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            Faults.point("rebuild_before_commit");
            bumpVersion(c);
            return null;
        });
    }

    public boolean isIndexed(int bookId) throws SQLException {
        return db.withConnection(c -> currentSha(c, bookId) != null);
    }

    public Status status() throws SQLException {
        return db.read(c -> {
            long books;
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM books"); ResultSet rs = ps.executeQuery()) {
                books = rs.getLong(1);
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT version, last_update FROM index_state");
                 ResultSet rs = ps.executeQuery()) {
                long version = rs.getLong(1);
                long lastUpdate = rs.getLong(2);
                return new Status(books, version, rs.wasNull() ? null : lastUpdate, sizeOnDisk());
            }
        });
    }

    private long sizeOnDisk() {
        Path file = db.file();
        return size(file) + size(file.resolveSibling(file.getFileName() + "-wal"));
    }

    private static long size(Path path) {
        try {
            return Files.exists(path) ? Files.size(path) : 0;
        } catch (IOException e) {
            return 0;
        }
    }

    private static String currentSha(Connection c, int bookId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT content_sha256 FROM books WHERE book_id = ?")) {
            ps.setInt(1, bookId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static void insertBook(Connection c, BookIndex book) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO postings (term, book_id, tf) VALUES (?, ?, ?)")) {
            // In the order of the clustered key, each B-tree leaf is visited once: fewer page reloads.
            for (Map.Entry<String, Integer> posting : new TreeMap<>(book.frequencies()).entrySet()) {
                ps.setString(1, posting.getKey());
                ps.setInt(2, book.bookId());
                ps.setInt(3, posting.getValue());
                ps.addBatch();
            }
            ps.executeBatch();
        }
        MetadataExtractor.Metadata metadata = book.metadata();
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT OR REPLACE INTO books (book_id, title, author, author_norm, language, year, word_count, "
                        + "unique_terms, content_sha256, indexed_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setInt(1, book.bookId());
            ps.setString(2, metadata.title());
            ps.setString(3, metadata.author());
            ps.setString(4, metadata.author() == null ? null : com.calima.search.common.Tokenizer.normalize(metadata.author()));
            ps.setString(5, metadata.language());
            if (metadata.year() == null) {
                ps.setNull(6, java.sql.Types.INTEGER);
            } else {
                ps.setInt(6, metadata.year());
            }
            ps.setInt(7, book.wordCount());
            ps.setInt(8, book.frequencies().size());
            ps.setString(9, book.contentSha256());
            ps.setLong(10, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    private static void bumpVersion(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE index_state SET version = version + 1, last_update = ?")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }
}
