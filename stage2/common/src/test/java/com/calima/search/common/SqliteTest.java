package com.calima.search.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteTest {

    private static final long WAL_CEILING_BYTES = 96L * 1024 * 1024;

    @TempDir
    Path dir;

    private int count(Sqlite db) throws SQLException {
        return db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM t"); ResultSet rs = ps.executeQuery()) {
                return rs.getInt(1);
            }
        });
    }

    private Sqlite writerWithTable() throws SQLException {
        Sqlite writer = Sqlite.writer(dir.resolve("sub/db.sqlite"), 2, 200, "FULL");
        writer.withConnection(c -> {
            Sqlite.execute(c, "CREATE TABLE t (id INTEGER)");
            return null;
        });
        return writer;
    }

    @Test
    void failedWriteIsRolledBack() throws Exception {
        try (Sqlite db = writerWithTable()) {
            assertThrows(IllegalStateException.class, () -> db.write(c -> {
                Sqlite.execute(c, "INSERT INTO t VALUES (1)");
                throw new IllegalStateException("boom");
            }));
            assertEquals(0, count(db));
        }
    }

    @Test
    void readerKeepsItsSnapshotWhileAWriteCommits() throws Exception {
        try (Sqlite writer = writerWithTable();
             Sqlite reader = Sqlite.reader(dir.resolve("sub/db.sqlite"), 1, 200)) {
            int[] counts = reader.read(c -> {
                int before = count(c);
                writer.write(w -> {
                    Sqlite.execute(w, "INSERT INTO t VALUES (1)");
                    return null;
                });
                return new int[] {before, count(c)};
            });
            assertEquals(0, counts[0]);
            assertEquals(0, counts[1], "the open read transaction must not see the new commit");
            assertEquals(1, count(reader));
        }
    }

    private static int count(java.sql.Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM t"); ResultSet rs = ps.executeQuery()) {
            return rs.getInt(1);
        }
    }

    @Test
    void secondWriterGetsBusyWhenTheLockIsNotReleased() throws Exception {
        try (Sqlite first = writerWithTable();
             Sqlite second = Sqlite.writer(dir.resolve("sub/db.sqlite"), 1, 100, "FULL")) {
            CountDownLatch holding = new CountDownLatch(1);
            CountDownLatch finish = new CountDownLatch(1);
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<?> holder = executor.submit(() -> first.write(c -> {
                holding.countDown();
                try {
                    finish.await();
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
                return null;
            }));
            holding.await();
            SQLException busy = assertThrows(SQLException.class, () -> second.write(c -> null));
            assertTrue(Sqlite.isBusy(busy), busy.toString());
            finish.countDown();
            holder.get();
            executor.shutdown();
        }
    }

    /**
     * Without an in-process queue, SQLite's busy handler lets one thread keep the lock while the
     * others sleep: JMH measured single operations of several seconds at 4 threads.
     */
    @Test
    void writersInOneProcessTakeTurnsWithoutStarving() throws Exception {
        int threads = 4;
        int writesPerThread = 150;
        try (Sqlite db = writerWithTable()) {
            ExecutorService executor = Executors.newFixedThreadPool(threads);
            java.util.List<Future<Long>> workers = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                workers.add(executor.submit(() -> {
                    long worstNanos = 0;
                    for (int write = 0; write < writesPerThread; write++) {
                        long start = System.nanoTime();
                        db.write(c -> {
                            Sqlite.execute(c, "INSERT INTO t VALUES (1)");
                            return null;
                        });
                        worstNanos = Math.max(worstNanos, System.nanoTime() - start);
                    }
                    return worstNanos;
                }));
            }
            long worstNanos = 0;
            for (Future<Long> worker : workers) {
                worstNanos = Math.max(worstNanos, worker.get());
            }
            executor.shutdown();
            assertEquals(threads * writesPerThread, count(db));
            assertTrue(worstNanos < 500_000_000L, "worst write took " + worstNanos / 1_000_000 + " ms");
        }
    }

    /** Writes {@code rows} rows of 500 bytes per transaction and returns the largest log seen after a commit. */
    private long writeLoadAndPeakWal(Sqlite writer, Path wal, int transactions, int rows) throws SQLException, java.io.IOException {
        long peak = 0;
        for (int i = 0; i < transactions; i++) {
            writer.write(c -> {
                Sqlite.execute(c, "WITH RECURSIVE n(x) AS (SELECT 1 UNION ALL SELECT x + 1 FROM n WHERE x < " + rows + ") "
                        + "INSERT INTO load SELECT randomblob(500) FROM n");
                return null;
            });
            peak = Math.max(peak, java.nio.file.Files.size(wal));
        }
        return peak;
    }

    private Sqlite writerWithLoadTable(Path file) throws SQLException {
        Sqlite writer = Sqlite.writer(file, 2, 30_000, "NORMAL");
        writer.write(c -> {
            Sqlite.execute(c, "CREATE TABLE IF NOT EXISTS load (payload BLOB)");
            return null;
        });
        return writer;
    }

    /**
     * Two replicas alternating as writers leave no moment in which the log can restart: one begins
     * while the other checkpoints. The integration benchmark saw 1.9 GB of log for a 61 MB index.
     */
    @Test
    void writeAheadLogStaysBoundedWhenReplicasCompeteToWrite() throws Exception {
        Path file = dir.resolve("replicas.db");
        Path wal = dir.resolve("replicas.db-wal");
        int transactions = 15;
        try (Sqlite first = writerWithLoadTable(file); Sqlite second = writerWithLoadTable(file)) {
            ExecutorService executor = Executors.newFixedThreadPool(4);
            java.util.List<Future<Long>> writers = new java.util.ArrayList<>();
            for (Sqlite replica : java.util.List.of(first, second, first, second)) {
                writers.add(executor.submit(() -> writeLoadAndPeakWal(replica, wal, transactions, 16_000)));
            }
            long peak = 0;
            for (Future<Long> writer : writers) {
                peak = Math.max(peak, writer.get());
            }
            executor.shutdown();
            assertTrue(peak < WAL_CEILING_BYTES, "the log reached " + peak / (1024 * 1024) + " MB");
        }
    }

    /** Constant reads have the same effect on the log, because a reader pins the frames it can see. */
    @Test
    void writeAheadLogStaysBoundedUnderConstantReads() throws Exception {
        Path file = dir.resolve("reads.db");
        Path wal = dir.resolve("reads.db-wal");
        try (Sqlite writer = writerWithLoadTable(file); Sqlite reader = Sqlite.reader(file, 4, 30_000)) {
            java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean(true);
            ExecutorService executor = Executors.newFixedThreadPool(4);
            java.util.List<Future<Integer>> readers = new java.util.ArrayList<>();
            for (int i = 0; i < 4; i++) {
                readers.add(executor.submit(() -> {
                    int reads = 0;
                    while (running.get()) {
                        // Held for a while: four such readers overlap, so one is always active.
                        reader.read(c -> {
                            try (PreparedStatement ps = c.prepareStatement("SELECT rowid FROM load LIMIT 50"); ResultSet rs = ps.executeQuery()) {
                                rs.next();
                            }
                            try {
                                Thread.sleep(8);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            return null;
                        });
                        reads++;
                    }
                    return reads;
                }));
            }
            long peak;
            try {
                peak = writeLoadAndPeakWal(writer, wal, 360, 2_000);
            } finally {
                running.set(false);
            }
            for (Future<Integer> read : readers) {
                assertTrue(read.get() > 0);
            }
            executor.shutdown();
            assertTrue(peak < WAL_CEILING_BYTES, "the log reached " + peak / (1024 * 1024) + " MB");
        }
    }

    @Test
    void aWriterThatWaitsTooLongInItsProcessGetsADocumentedError() throws Exception {
        try (Sqlite db = writerWithTable()) {
            CountDownLatch holding = new CountDownLatch(1);
            CountDownLatch finish = new CountDownLatch(1);
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<?> holder = executor.submit(() -> db.write(c -> {
                holding.countDown();
                try {
                    finish.await();
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
                return null;
            }));
            holding.await();
            ApiException busy = assertThrows(ApiException.class, () -> db.write(c -> null));
            assertEquals(503, busy.status());
            assertEquals("database_busy", busy.code());
            finish.countDown();
            holder.get();
            executor.shutdown();
        }
    }

    @Test
    void exhaustedPoolReportsADocumentedError() throws Exception {
        try (Sqlite db = writerWithTable()) {
            CountDownLatch holding = new CountDownLatch(2);
            CountDownLatch finish = new CountDownLatch(1);
            ExecutorService executor = Executors.newFixedThreadPool(2);
            for (int i = 0; i < 2; i++) {
                executor.submit(() -> db.withConnection(c -> {
                    holding.countDown();
                    try {
                        finish.await();
                    } catch (InterruptedException e) {
                        throw new IllegalStateException(e);
                    }
                    return null;
                }));
            }
            holding.await();
            ApiException full = assertThrows(ApiException.class, () -> db.withConnection(c -> null));
            assertEquals(503, full.status());
            assertEquals("service_busy", full.code());
            finish.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
        }
    }
}
