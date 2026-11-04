package com.calima.search.common;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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

    private final Path file;
    private final boolean readOnly;
    private final int busyTimeoutMs;
    private final String synchronousMode;
    private final BlockingQueue<Connection> idle;
    private final AtomicInteger created = new AtomicInteger();
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

    /** Runs the work inside {@code BEGIN IMMEDIATE}, the single write lock across processes. */
    public <T> T write(Work<T> work) throws SQLException {
        return transaction("BEGIN IMMEDIATE", work);
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
