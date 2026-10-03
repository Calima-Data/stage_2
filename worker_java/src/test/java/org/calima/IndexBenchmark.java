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
 * One operation = adding one new book's 500 terms to an index that already holds a
 * 5000-term vocabulary. 450 of those terms already exist (so the operation hits the
 * "term already present" path) and 50 are new, which is what incremental indexing
 * actually looks like. Same workload as the Python and Node.js harnesses.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(1)
@Warmup(iterations = 3)
@Measurement(iterations = 8)
public class IndexBenchmark {

    static final int VOCAB_SIZE = 5000;
    static final int EXISTING_TERMS = 450;
    static final int NEW_TERMS = 50;
    static final int TEST_BOOK_ID = 9999;

    @State(Scope.Thread)
    public static class Index {
        Path dir;
        Path jsonPath;
        Path dbPath;
        Path folderDir;
        Set<String> bookWords;

        @Setup(Level.Iteration)
        public void setup() throws IOException, SQLException {
            dir = Files.createTempDirectory("index_bench_java");
            jsonPath = dir.resolve("inverted_index.json");
            dbPath = dir.resolve("inverted_index.db");
            folderDir = dir.resolve("folders");
            Files.createDirectories(folderDir);

            List<String> vocabulary = new ArrayList<>(VOCAB_SIZE);
            for (int i = 0; i < VOCAB_SIZE; i++) {
                vocabulary.add("word" + i);
            }

            List<String> shuffled = new ArrayList<>(vocabulary);
            Collections.shuffle(shuffled);
            bookWords = new LinkedHashSet<>(shuffled.subList(0, EXISTING_TERMS));
            for (int i = 0; i < NEW_TERMS; i++) {
                bookWords.add("new_word" + i);
            }

            // the whole vocabulary already belongs to book 1 in every structure
            Map<String, List<Integer>> seed = new LinkedHashMap<>();
            for (String term : vocabulary) {
                List<Integer> postings = new ArrayList<>(1);
                postings.add(1);
                seed.put(term, postings);
            }
            InvertedIndex.writeJson(jsonPath, seed);

            InvertedIndex.createSqliteSchema(dbPath);
            InvertedIndex.insertSqlite(dbPath, 1, seed.keySet());

            Set<String> vocabSet = new LinkedHashSet<>(vocabulary);
            Set<String> seedFolderTerms = new LinkedHashSet<>();
            for (String term : bookWords) {
                if (vocabSet.contains(term)) {
                    seedFolderTerms.add(term);
                }
            }
            InvertedIndex.insertFolders(folderDir, 1, seedFolderTerms);
        }

        @TearDown(Level.Iteration)
        public void tearDown() throws IOException {
            DatalakeBenchmark.deleteRecursive(dir);
        }
    }

    @Benchmark
    public void sqlitePostings(Index index) throws SQLException {
        InvertedIndex.insertSqlite(index.dbPath, TEST_BOOK_ID, index.bookWords);
    }

    @Benchmark
    public void monolithicJson(Index index) throws IOException {
        InvertedIndex.insertJson(index.jsonPath, TEST_BOOK_ID, index.bookWords);
    }

    @Benchmark
    public void folderHierarchy(Index index) throws IOException {
        InvertedIndex.insertFolders(index.folderDir, TEST_BOOK_ID, index.bookWords);
    }
}
