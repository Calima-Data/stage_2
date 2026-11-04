package com.calima.search.indexing.benchmark;

import com.calima.search.common.Sqlite;
import com.calima.search.common.Tokenizer;
import com.calima.search.indexing.DatamartRepository;
import com.calima.search.indexing.MetadataExtractor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

/**
 * The serialized part of indexing: one atomic publication of a real book's postings. Every thread
 * competes for the single SQLite write lock, so this measures lock contention, and comparing the
 * synchronous modes shows the cost of waiting for the disk on each commit.
 *
 * <p>Each operation adds a new book, so the database grows during the run, as it does in production.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class DatamartPublishBenchmark {

    private static final int BUSY_TIMEOUT_MS = 120_000;

    @Param({"FULL", "NORMAL"})
    public String synchronous;

    private Path dir;
    private Sqlite db;
    private DatamartRepository repository;
    private Map<String, Integer> frequencies;
    private MetadataExtractor.Metadata metadata;
    private final AtomicInteger nextBook = new AtomicInteger();

    @Setup
    public void open() throws IOException, SQLException {
        dir = Files.createTempDirectory("jmh-datamart");
        db = Sqlite.writer(dir.resolve("search.db"), 32, BUSY_TIMEOUT_MS, synchronous);
        repository = new DatamartRepository(db);
        frequencies = Tokenizer.termFrequencies(TextProcessingBenchmark.resource("/alice_body.txt"));
        metadata = new MetadataExtractor.Metadata("Alice", "Lewis Carroll", "en", 1865);
    }

    @TearDown
    public void close() throws IOException {
        db.close();
        try (Stream<Path> files = Files.walk(dir)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    @Benchmark
    public boolean publishNewBook() throws SQLException {
        int id = nextBook.incrementAndGet();
        return repository.publish(new DatamartRepository.BookIndex(id, metadata, frequencies, "sha-" + id));
    }
}
