package com.calima.search.indexing.benchmark;

import com.calima.search.common.Tokenizer;
import com.calima.search.indexing.MetadataExtractor;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/**
 * CPU work of indexing one real book (Alice's Adventures in Wonderland), without any I/O. It runs
 * outside the database lock in production, so running it with several threads shows how far the
 * parsing stage can scale on this host.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class TextProcessingBenchmark {

    private String body;
    private String header;
    private MetadataExtractor extractor;

    @Setup
    public void load() throws IOException {
        body = resource("/alice_body.txt");
        header = resource("/alice_header.txt");
        extractor = new MetadataExtractor(Map.of());
    }

    static String resource(String name) throws IOException {
        try (InputStream in = TextProcessingBenchmark.class.getResourceAsStream(name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Benchmark
    public Map<String, Integer> tokenizeBook() {
        return Tokenizer.termFrequencies(body);
    }

    @Benchmark
    public MetadataExtractor.Metadata extractMetadata() {
        return extractor.extract(11, header);
    }
}
