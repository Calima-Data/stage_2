package org.calima;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmark for the three inverted-index layouts.
 *
 * One operation = adding ONE NEW book's 500 terms to an index that holds a 5000-term
 * vocabulary (450 of the 500 terms already exist, 50 are new). The index is rebuilt from
 * scratch before every invocation (Level.Invocation, not timed), so each measured
 * operation really is "index a book that is not in the index yet". Reusing the state
 * would turn every invocation after the first into "re-insert a book that is already
 * there", which is a different and cheaper operation. Only the structure under test is
 * seeded. The Python and Node.js harnesses measure the same operation.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 8, time = 2)
public class IndexBenchmark {

    static final int VOCAB_SIZE = 5000;
    static final int EXISTING_TERMS = 450;
    static final int NEW_TERMS = 50;
    static final int TEST_BOOK_ID = 9999;

    /** Shared by the three states: the vocabulary and the 500 terms of the new book. */
    static class Workload {
        final List<String> vocabulary = new ArrayList<>(VOCAB_SIZE);
        final Set<String> bookWords = new LinkedHashSet<>();

        Workload() {
            for (int i = 0; i < VOCAB_SIZE; i++) {
                vocabulary.add("word" + i);
            }
            List<String> shuffled = new ArrayList<>(vocabulary);
            Collections.shuffle(shuffled);
            bookWords.addAll(shuffled.subList(0, EXISTING_TERMS));
            for (int i = 0; i < NEW_TERMS; i++) {
                bookWords.add("new_word" + i);
            }
        }
    }

    @State(Scope.Thread)
    public static class SqliteState {
        Path dir;
        Path dbPath;
        Workload workload;

        @Setup(Level.Invocation)
        public void setup() throws IOException, SQLException {
            workload = new Workload();
            dir = Files.createTempDirectory("index_bench_sqlite");
            dbPath = dir.resolve("inverted_index.db");
            InvertedIndex.createSqliteSchema(dbPath);
            InvertedIndex.insertSqlite(dbPath, 1, new LinkedHashSet<>(workload.vocabulary));
        }

        @TearDown(Level.Invocation)
        public void tearDown() throws IOException {
            DatalakeBenchmark.deleteRecursive(dir);
        }
    }

    @State(Scope.Thread)
    public static class JsonState {
        Path dir;
        Path jsonPath;
        Workload workload;

        @Setup(Level.Invocation)
        public void setup() throws IOException {
            workload = new Workload();
            dir = Files.createTempDirectory("index_bench_json");
            jsonPath = dir.resolve("inverted_index.json");
            Map<String, List<Integer>> seed = new LinkedHashMap<>();
            for (String term : workload.vocabulary) {
                List<Integer> postings = new ArrayList<>(1);
                postings.add(1);
                seed.put(term, postings);
            }
            InvertedIndex.writeJson(jsonPath, seed);
        }

        @TearDown(Level.Invocation)
        public void tearDown() throws IOException {
            DatalakeBenchmark.deleteRecursive(dir);
        }
    }

    @State(Scope.Thread)
    public static class FolderState {
        Path dir;
        Path folderDir;
        Workload workload;

        @Setup(Level.Invocation)
        public void setup() throws IOException {
            workload = new Workload();
            dir = Files.createTempDirectory("index_bench_folders");
            folderDir = dir.resolve("folders");
            Files.createDirectories(folderDir);
            // only the terms the new book touches that already exist need a file
            Set<String> vocabSet = new LinkedHashSet<>(workload.vocabulary);
            Set<String> existing = new LinkedHashSet<>();
            for (String term : workload.bookWords) {
                if (vocabSet.contains(term)) {
                    existing.add(term);
                }
            }
            InvertedIndex.insertFolders(folderDir, 1, existing);
        }

        @TearDown(Level.Invocation)
        public void tearDown() throws IOException {
            DatalakeBenchmark.deleteRecursive(dir);
        }
    }

    @Benchmark
    public void sqlitePostings(SqliteState s) throws SQLException {
        InvertedIndex.insertSqlite(s.dbPath, TEST_BOOK_ID, s.workload.bookWords);
    }

    @Benchmark
    public void monolithicJson(JsonState s) throws IOException {
        InvertedIndex.insertJson(s.jsonPath, TEST_BOOK_ID, s.workload.bookWords);
    }

    @Benchmark
    public void folderHierarchy(FolderState s) throws IOException {
        InvertedIndex.insertFolders(s.folderDir, TEST_BOOK_ID, s.workload.bookWords);
    }
}
