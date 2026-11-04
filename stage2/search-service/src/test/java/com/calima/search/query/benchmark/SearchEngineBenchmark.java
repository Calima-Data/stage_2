package com.calima.search.query.benchmark;

import com.calima.search.common.Sqlite;
import com.calima.search.query.ResultCache;
import com.calima.search.query.SearchEngine;
import com.calima.search.query.SearchRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
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
 * Query execution without HTTP, on a synthetic datamart of 500 books. The result cache is disabled,
 * so each operation does the full work: "common" reads a posting for every book, "rare" for a few,
 * and "twoTerms" intersects two lists. Cost should follow the number of postings read.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class SearchEngineBenchmark {

    private static final int BOOKS = 500;
    private static final int FILLER_TERMS_PER_BOOK = 1000;
    private static final int FILLER_VOCABULARY = 20_000;

    @Param({"common", "rare", "twoTerms"})
    public String queryKind;

    private Path dir;
    private Sqlite reader;
    private SearchEngine engine;
    private SearchRequest request;

    @Setup
    public void prepare() throws IOException, SQLException {
        dir = Files.createTempDirectory("jmh-search");
        Path file = dir.resolve("search.db");
        try (Sqlite writer = Sqlite.writer(file, 1, 60_000, "NORMAL")) {
            writer.write(SearchEngineBenchmark::seed);
        }
        reader = Sqlite.reader(file, 32, 60_000);
        engine = new SearchEngine(reader, new ResultCache(0));
        String query = switch (queryKind) {
            case "common" -> "commonterm";
            case "rare" -> "rareterm";
            default -> "commonterm halfterm";
        };
        request = SearchRequest.parse(Map.of("q", List.of(query)));
    }

    private static Void seed(java.sql.Connection c) throws SQLException {
        Sqlite.execute(c, "CREATE TABLE books (book_id INTEGER PRIMARY KEY, title TEXT, author TEXT, author_norm TEXT, "
                + "language TEXT NOT NULL, year INTEGER, word_count INTEGER NOT NULL, unique_terms INTEGER NOT NULL, "
                + "content_sha256 TEXT NOT NULL, indexed_at INTEGER NOT NULL)");
        Sqlite.execute(c, "CREATE TABLE postings (term TEXT NOT NULL, book_id INTEGER NOT NULL, tf INTEGER NOT NULL, "
                + "PRIMARY KEY (term, book_id)) WITHOUT ROWID");
        Sqlite.execute(c, "CREATE TABLE index_state (id INTEGER PRIMARY KEY, version INTEGER NOT NULL, last_update INTEGER)");
        Sqlite.execute(c, "INSERT INTO index_state VALUES (1, 1, 0)");
        Random random = new Random(42);
        try (PreparedStatement book = c.prepareStatement("INSERT INTO books VALUES (?, ?, 'Author', 'author', 'en', 1900, ?, ?, 'sha', 0)");
             PreparedStatement posting = c.prepareStatement("INSERT OR IGNORE INTO postings VALUES (?, ?, ?)")) {
            for (int id = 1; id <= BOOKS; id++) {
                book.setInt(1, id);
                book.setString(2, "Book " + id);
                book.setInt(3, 5000 + random.nextInt(5000));
                book.setInt(4, FILLER_TERMS_PER_BOOK);
                book.executeUpdate();
                addPosting(posting, "commonterm", id, 1 + random.nextInt(50));
                if (id % 2 == 0) {
                    addPosting(posting, "halfterm", id, 1 + random.nextInt(20));
                }
                if (id % 100 == 0) {
                    addPosting(posting, "rareterm", id, 1 + random.nextInt(5));
                }
                for (int i = 0; i < FILLER_TERMS_PER_BOOK; i++) {
                    addPosting(posting, "filler" + random.nextInt(FILLER_VOCABULARY), id, 1 + random.nextInt(9));
                }
            }
            posting.executeBatch();
        }
        return null;
    }

    private static void addPosting(PreparedStatement posting, String term, int id, int tf) throws SQLException {
        posting.setString(1, term);
        posting.setInt(2, id);
        posting.setInt(3, tf);
        posting.addBatch();
    }

    @TearDown
    public void close() throws IOException {
        reader.close();
        try (Stream<Path> files = Files.walk(dir)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    @Benchmark
    public SearchEngine.Answer search() throws SQLException {
        return engine.search(request);
    }
}
