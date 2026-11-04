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
