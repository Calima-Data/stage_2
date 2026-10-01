import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Java port of worker_python/test_benchmark.py.
 * Measures write throughput of the three datalake layouts using synthetic
 * data, with the same shape (1000 books/round, 3 rounds, no cleanup between
 * rounds) so numbers are directly comparable to the Python benchmark.
 */
public class DatalakeBenchmark {

    static final Path TEST_DIR = Paths.get("..", "datalake_benchmark_tmp_java").toAbsolutePath().normalize();
    static final int NUM_BOOKS = 1000;
    static final String TEST_HEADER = "Title: Benchmark Test\nAuthor: Data Team\nLanguage: English";
    static final String TEST_BODY = "Small simulated text to focus purely on filesystem I/O overhead. ".repeat(20);
    static final int ROUNDS = 3;

    static void saveTimeBased(int i) throws IOException {
        LocalDateTime now = LocalDateTime.now();
        Path outputPath = TEST_DIR.resolve(now.format(DateTimeFormatter.ofPattern("yyyyMMdd")))
                .resolve(now.format(DateTimeFormatter.ofPattern("HH")));
        Files.createDirectories(outputPath);
        Files.writeString(outputPath.resolve(i + ".header.txt"), TEST_HEADER, StandardCharsets.UTF_8);
        Files.writeString(outputPath.resolve(i + ".body.txt"), TEST_BODY, StandardCharsets.UTF_8);
    }

    static void saveBookBased(int i) throws IOException {
        Path outputPath = TEST_DIR.resolve("books").resolve(String.valueOf(i));
        Files.createDirectories(outputPath);
        Files.writeString(outputPath.resolve("header.txt"), TEST_HEADER, StandardCharsets.UTF_8);
        Files.writeString(outputPath.resolve("body.txt"), TEST_BODY, StandardCharsets.UTF_8);
    }

    static void saveRangeBased(int i, int rangeSize) throws IOException {
        int lower = ((i - 1) / rangeSize) * rangeSize + 1;
        int upper = lower + rangeSize - 1;
        Path outputPath = TEST_DIR.resolve("batch_%d_%d".formatted(lower, upper));
        Files.createDirectories(outputPath);
        Files.writeString(outputPath.resolve(i + ".header.txt"), TEST_HEADER, StandardCharsets.UTF_8);
        Files.writeString(outputPath.resolve(i + ".body.txt"), TEST_BODY, StandardCharsets.UTF_8);
    }

    interface Batch { void run() throws IOException; }

    static void deleteRecursive(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (var walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.delete(p); } catch (IOException ignored) {}
            });
        }
    }

    static double[] runBenchmark(String name, Batch batch) throws IOException {
        deleteRecursive(TEST_DIR);
        Files.createDirectories(TEST_DIR);

        double[] timesMs = new double[ROUNDS];
        for (int r = 0; r < ROUNDS; r++) {
            long start = System.nanoTime();
            batch.run();
            long end = System.nanoTime();
            timesMs[r] = (end - start) / 1_000_000.0;
        }
        deleteRecursive(TEST_DIR);
        return timesMs;
    }

    static void printStats(String name, double[] times) {
        double min = Arrays.stream(times).min().orElse(0);
        double max = Arrays.stream(times).max().orElse(0);
        double mean = Arrays.stream(times).average().orElse(0);
        double[] sorted = times.clone();
        Arrays.sort(sorted);
        double median = sorted[sorted.length / 2];
        double variance = Arrays.stream(times).map(t -> Math.pow(t - mean, 2)).sum() / (times.length - 1);
        double stddev = Math.sqrt(variance);
        System.out.printf(Locale.US, "%-16s min=%10.4f  max=%10.4f  mean=%10.4f  median=%10.4f  stddev=%10.4f  (ms)%n",
                name, min, max, mean, median, stddev);
    }

    public static void main(String[] args) throws IOException {
        System.out.println("Datalake structure benchmark (Java) - " + ROUNDS + " rounds x " + NUM_BOOKS + " books");

        double[] timeBased = runBenchmark("time_based", () -> {
            for (int i = 0; i < NUM_BOOKS; i++) saveTimeBased(i);
        });
        double[] bookBased = runBenchmark("book_based", () -> {
            for (int i = 0; i < NUM_BOOKS; i++) saveBookBased(i);
        });
        double[] rangeBased = runBenchmark("range_based", () -> {
            for (int i = 0; i < NUM_BOOKS; i++) saveRangeBased(i, 100);
        });

        printStats("time_based", timeBased);
        printStats("book_based", bookBased);
        printStats("range_based", rangeBased);
    }
}
