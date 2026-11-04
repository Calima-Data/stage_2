package com.calima.search.common;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

/**
 * SQLite access shared by the services: a bounded connection pool plus explicit transactions.
 *
 * <p>Threads of one process wait for a free connection in the pool. Separate processes are
 * coordinated by SQLite's own file locks: {@code BEGIN IMMEDIATE} admits one writer at a time, and
 * WAL mode lets readers keep their snapshot while it writes. This relies on a local file system,
 * which is one of the assumptions Stage 3 must revisit.
 */
public final class Sqlite implements AutoCloseable {

    /** A database operation that may fail with an SQL error. */
    @FunctionalInterface
    public interface Work<T> {
        T run(Connection connection) throws SQLException;
    }

    private static final Logger LOG = LoggerFactory.getLogger(Sqlite.class);
    private static final long WAL_LIMIT_BYTES = 64L * 1024 * 1024;
    private static final long CHECKPOINT_RETRY_MS = 5;

    private final Path file;
    private final boolean readOnly;
    private final int busyTimeoutMs;
    private final String synchronousMode;
    private final BlockingQueue<Connection> idle;
    private final AtomicInteger created = new AtomicInteger();
    private final ReentrantLock processWriteLock = new ReentrantLock(true);
    private final int poolSize;
    private final int poolWaitMs;

    private Sqlite(Path file, boolean readOnly, int poolSize, int busyTimeoutMs, String synchronousMode, int poolWaitMs) {
        this.file = file;
        this.readOnly = readOnly;
        this.poolSize = poolSize;
        this.busyTimeoutMs = busyTimeoutMs;
        this.synchronousMode = synchronousMode;
        this.poolWaitMs = poolWaitMs;
        this.idle = new ArrayBlockingQueue<>(poolSize);
    }

    /** Opens a database this process writes to, creating its parent directory and enabling WAL. */
    public static Sqlite writer(Path file, int poolSize, int busyTimeoutMs, String synchronousMode) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return new Sqlite(file, false, poolSize, busyTimeoutMs, synchronousMode, busyTimeoutMs);
    }

    /** Opens a database that another service owns; this process can only read it. */
    public static Sqlite reader(Path file, int poolSize, int busyTimeoutMs) {
        return new Sqlite(file, true, poolSize, busyTimeoutMs, null, busyTimeoutMs);
    }

    public Path file() {
        return file;
    }

    /** Runs the work on a pooled connection, in auto-commit mode. */
    public <T> T withConnection(Work<T> work) throws SQLException {
        Connection connection = borrow();
        try {
            return work.run(connection);
        } finally {
            release(connection);
        }
    }

    /**
     * Runs the work inside {@code BEGIN IMMEDIATE}, the single write lock across processes.
     *
     * <p>Threads of this process first queue on a fair lock. Left to SQLite's busy handler alone,
     * they sleep with growing delays even when the database is free, so one thread keeps winning
     * while others starve: JMH measured single operations of several seconds at 4 threads. This lock
     * orders only this process; replicas still wait on SQLite's own lock.
     */
    public <T> T write(Work<T> work) throws SQLException {
        try {
            if (!processWriteLock.tryLock(poolWaitMs, TimeUnit.MILLISECONDS)) {
                throw new ApiException(503, "database_busy", "Too many writers are waiting for the database", 1);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(503, "database_busy", "Interrupted while waiting for the database", 1);
        }
        try {
            emptyLogIfLarge();
            return transaction("BEGIN IMMEDIATE", work);
        } finally {
            processWriteLock.unlock();
        }
    }

    /**
     * Empties a write-ahead log that has outgrown {@link #WAL_LIMIT_BYTES}.
     *
     * <p>SQLite's automatic checkpoint copies pages to the database but only restarts the log when no
     * connection is using it. Constant reads, or replicas taking turns to write, leave no such moment:
     * the log then grows with everything ever written, and benchmarks saw 1.9 GB for a 61 MB index. A
     * {@code TRUNCATE} checkpoint waits for that moment instead.
     *
     * <p>Writers wait here until the log is small. A second checkpoint gets {@code SQLITE_BUSY} at once,
     * without waiting, so a replica that simply carried on writing would starve the checkpoint already
     * running. After the busy timeout the write goes ahead anyway: a large log is better than no write.
     */
    private void emptyLogIfLarge() {
        Path log = file.resolveSibling(file.getFileName() + "-wal");
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(busyTimeoutMs);
        try {
            while (Files.exists(log) && Files.size(log) >= WAL_LIMIT_BYTES && System.nanoTime() < deadline) {
                withConnection(connection -> {
                    try (Statement statement = connection.createStatement();
                         ResultSet result = statement.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)")) {
                        return result.next();
                    }
                });
                if (Files.size(log) >= WAL_LIMIT_BYTES) {
                    Thread.sleep(CHECKPOINT_RETRY_MS);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.io.IOException | SQLException | ApiException e) {
            LOG.warn("Could not empty the write-ahead log of {}: {}", file, e.toString());
        }
    }

    /** Runs the work inside one read transaction, so every statement sees the same snapshot. */
    public <T> T read(Work<T> work) throws SQLException {
        return transaction("BEGIN", work);
    }

    private <T> T transaction(String begin, Work<T> work) throws SQLException {
        return withConnection(connection -> {
            execute(connection, begin);
            boolean committed = false;
            try {
                T result = work.run(connection);
                execute(connection, "COMMIT");
                committed = true;
                return result;
            } finally {
                if (!committed) {
                    rollbackQuietly(connection);
                }
            }
        });
    }

    public static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /** True when SQLite refused the work because another transaction holds the lock. */
    public static boolean isBusy(SQLException e) {
        if (e instanceof SQLiteException) {
            SQLiteErrorCode code = ((SQLiteException) e).getResultCode();
            return code == SQLiteErrorCode.SQLITE_BUSY || code == SQLiteErrorCode.SQLITE_LOCKED
                    || code.name().startsWith("SQLITE_BUSY") || code.name().startsWith("SQLITE_LOCKED");
        }
        return false;
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            execute(connection, "ROLLBACK");
        } catch (SQLException ignored) {
            // SQLite has already rolled back after errors such as a failed COMMIT.
        }
    }

    private Connection borrow() throws SQLException {
        Connection connection = idle.poll();
        if (connection != null) {
            return connection;
        }
        if (created.incrementAndGet() <= poolSize) {
            try {
                return open();
            } catch (SQLException | RuntimeException e) {
                created.decrementAndGet();
                throw e;
            }
        }
        created.decrementAndGet();
        try {
            connection = idle.poll(poolWaitMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(503, "service_busy", "Interrupted while waiting for a database connection", 1);
        }
        if (connection == null) {
            throw new ApiException(503, "service_busy", "All database connections are in use", 1);
        }
        return connection;
    }

    private void release(Connection connection) {
        if (!idle.offer(connection)) {
            closeQuietly(connection);
            created.decrementAndGet();
        }
    }

    private Connection open() throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.setBusyTimeout(busyTimeoutMs);
        if (readOnly) {
            config.setReadOnly(true);
        } else {
            config.setJournalMode(SQLiteConfig.JournalMode.WAL);
            config.setSynchronous(SQLiteConfig.SynchronousMode.valueOf(synchronousMode));
        }
        return DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath(), config.toProperties());
    }

    private static void closeQuietly(Connection connection) {
        try {
            connection.close();
        } catch (SQLException ignored) {
            // nothing useful to do while discarding a connection
        }
    }

    @Override
    public void close() {
        Connection connection;
        while ((connection = idle.poll()) != null) {
            closeQuietly(connection);
            created.decrementAndGet();
        }
    }
}
