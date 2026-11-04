package com.calima.search.ingestion.benchmark;

import com.calima.search.common.Sqlite;
import com.calima.search.ingestion.BookStore;
import com.calima.search.ingestion.GutenbergParser;
import com.calima.search.ingestion.IngestionLedger;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

/**
 * The local steps of ingesting one 500 KB book, without the download. Together they bound how fast
 * this service can ingest when the network is not the limit.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class IngestionBenchmark {

    private static final int BOOK_BYTES = 500_000;
    private static final int BUSY_TIMEOUT_MS = 120_000;

    private Path dir;
    private Sqlite db;
    private IngestionLedger ledger;
    private BookStore store;
    private String raw;
    private GutenbergParser.Book book;
    private final AtomicInteger next = new AtomicInteger();

    @Setup
    public void prepare() throws Exception {
        dir = Files.createTempDirectory("jmh-ingestion");
        db = Sqlite.writer(dir.resolve("ledger.db"), 32, BUSY_TIMEOUT_MS, "FULL");
        ledger = new IngestionLedger(db, 60_000);
        store = new BookStore(dir.resolve("lake"));
        String sentence = "It was the best of times and the words go on and on for a while.\n";
        raw = "Title: Bench\nAuthor: Team\nLanguage: English\n\n"
                + "*** START OF THE PROJECT GUTENBERG EBOOK BENCH ***\n"
                + sentence.repeat(BOOK_BYTES / sentence.length())
                + "*** END OF THE PROJECT GUTENBERG EBOOK BENCH ***\nlicense\n";
        book = GutenbergParser.split(raw);
    }

    @TearDown
    public void close() throws IOException {
        db.close();
        try (Stream<Path> files = Files.walk(dir)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    @Benchmark
    public GutenbergParser.Book splitHeaderAndBody() throws GutenbergParser.InvalidBookException {
        return GutenbergParser.split(raw);
    }

    /** Both files through a temporary name, with the flush to disk that makes the write durable. */
    @Benchmark
    public BookStore.Stored writeBookFiles() throws IOException {
        int id = next.incrementAndGet();
        return store.write("datalake/20310101/" + String.format("%02d", id % 24) + "/" + id, "r" + id, book);
    }

    /** The two ledger transactions of one ingestion: the claim and the completion. */
    @Benchmark
    public boolean claimAndCompleteInLedger() throws SQLException {
        int id = next.incrementAndGet();
        String owner = "r" + id;
        long now = System.currentTimeMillis();
        ledger.claim(id, owner, "datalake/20310101/00/" + id, now);
        return ledger.complete(id, owner, new BookStore.Stored(1, 1, "sha"), now);
    }
}
