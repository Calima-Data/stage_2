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
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * JMH benchmark for the three datalake layouts.
 *
 * One operation = storing NUM_BOOKS synthetic books (header + body) under one layout,
 * which is the same unit of work the Python (pytest-benchmark) and Node.js harnesses
 * measure, so the three languages stay comparable. The payload is synthetic on purpose:
 * it keeps network latency out of the measurement and isolates filesystem overhead.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(java.util.concurrent.TimeUnit.MILLISECONDS)
@Fork(1)
public class DatalakeBenchmark {

    static final int NUM_BOOKS = 1000;
    static final int RANGE_SIZE = 100;
    static final String TEST_HEADER = "Title: Benchmark Test\nAuthor: Data Team\nLanguage: English";
    static final String TEST_BODY =
            "Small simulated text to focus purely on filesystem I/O overhead. ".repeat(20);

    @State(Scope.Thread)
    public static class Lake {
        Path dir;

        @Setup(Level.Iteration)
        public void setup() throws IOException {
            dir = Files.createTempDirectory("datalake_bench_java");
        }

        @TearDown(Level.Iteration)
        public void tearDown() throws IOException {
            deleteRecursive(dir);
        }
    }

    @Benchmark
    @Warmup(iterations = 2)
    @Measurement(iterations = 5)
    public void timeBased(Lake lake) throws IOException {
        for (int i = 0; i < NUM_BOOKS; i++) {
            Datalake.saveTimeBased(lake.dir, i, TEST_HEADER, TEST_BODY);
        }
    }

    @Benchmark
    @Warmup(iterations = 2)
    @Measurement(iterations = 5)
    public void bookBased(Lake lake) throws IOException {
        for (int i = 0; i < NUM_BOOKS; i++) {
            Datalake.saveBookBased(lake.dir, i, TEST_HEADER, TEST_BODY);
        }
    }

    @Benchmark
    @Warmup(iterations = 2)
    @Measurement(iterations = 5)
    public void rangeBased(Lake lake) throws IOException {
        for (int i = 0; i < NUM_BOOKS; i++) {
            Datalake.saveRangeBased(lake.dir, i, TEST_HEADER, TEST_BODY, RANGE_SIZE);
        }
    }

    static void deleteRecursive(Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ignored) {
                    // a leftover file here only affects temp cleanup, not the measurement
                }
            });
        }
    }
}
