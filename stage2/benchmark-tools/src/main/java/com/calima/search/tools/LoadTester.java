package com.calima.search.tools;

import com.calima.search.common.Json;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Closed-loop HTTP load generator: each client thread sends a request, waits for the answer and
 * sends the next one, so client concurrency is an exact, reported number.
 *
 * <pre>
 * java -jar benchmark-tools-1.0.0.jar URLS QUERY_FILE CONCURRENCY WARMUP_S DURATION_S OUT_JSON
 * </pre>
 *
 * URLS is a comma-separated list of Search replicas; clients use them round-robin, which is the
 * request distribution under test. Each line of QUERY_FILE is {@code <query string> TAB <expected
 * total>}; a successful answer with another total counts as a mismatch, so that a configuration
 * cannot look fast by returning wrong results.
 */
public final class LoadTester {

    private record Query(String path, String expectedTotal) {
    }

    private static final class ClientResult {
        final long[] latencyNanos;
        int count;
        long errors;
        long mismatches;

        ClientResult() {
            latencyNanos = new long[1 << 20];
        }

        void record(long nanos) {
            if (count < latencyNanos.length) {
                latencyNanos[count++] = nanos;
            }
        }
    }

    private LoadTester() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 6) {
            throw new IllegalArgumentException("Usage: URLS QUERY_FILE CONCURRENCY WARMUP_S DURATION_S OUT_JSON");
        }
        String[] urls = args[0].split(",");
        List<Query> queries = readQueries(Path.of(args[1]));
        int concurrency = Integer.parseInt(args[2]);
        long warmupNanos = Duration.ofSeconds(Long.parseLong(args[3])).toNanos();
        long measureNanos = Duration.ofSeconds(Long.parseLong(args[4])).toNanos();

        long start = System.nanoTime();
        long measureFrom = start + warmupNanos;
        long measureTo = measureFrom + measureNanos;
        List<ClientResult> results = new ArrayList<>();
        CountDownLatch done = new CountDownLatch(concurrency);
        for (int i = 0; i < concurrency; i++) {
            ClientResult result = new ClientResult();
            results.add(result);
            String baseUrl = urls[i % urls.length];
            Thread client = new Thread(() -> {
                try {
                    runClient(baseUrl, queries, measureFrom, measureTo, result);
                } finally {
                    done.countDown();
                }
            }, "client-" + i);
            client.start();
        }
        done.await();

        writeSummary(Path.of(args[5]), results, concurrency, urls.length, measureNanos);
    }

    private static void runClient(String baseUrl, List<Query> queries, long measureFrom, long measureTo, ClientResult result) {
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build();
        int position = ThreadLocalRandom.current().nextInt(queries.size());
        while (true) {
            long now = System.nanoTime();
            if (now >= measureTo) {
                return;
            }
            Query query = queries.get(position++ % queries.size());
            boolean measuring = now >= measureFrom;
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + query.path()))
                    .timeout(Duration.ofSeconds(30)).GET().build();
            try {
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                long elapsed = System.nanoTime() - now;
                if (measuring) {
                    if (response.statusCode() != 200) {
                        result.errors++;
                    } else {
                        result.record(elapsed);
                        if (query.expectedTotal() != null && !response.body().contains("\"total\":" + query.expectedTotal() + ",")) {
                            result.mismatches++;
                        }
                    }
                }
            } catch (IOException e) {
                if (measuring) {
                    result.errors++;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static List<Query> readQueries(Path file) throws IOException {
        List<Query> queries = new ArrayList<>();
        for (String line : Files.readAllLines(file)) {
            if (!line.isBlank()) {
                String[] parts = line.split("\t");
                queries.add(new Query("/search?" + parts[0], parts.length > 1 ? parts[1] : null));
            }
        }
        if (queries.isEmpty()) {
            throw new IllegalArgumentException("No queries in " + file);
        }
        return queries;
    }

    private static void writeSummary(Path out, List<ClientResult> results, int concurrency, int replicas, long measureNanos)
            throws IOException {
        int total = results.stream().mapToInt(r -> r.count).sum();
        long[] all = new long[total];
        int position = 0;
        long errors = 0;
        long mismatches = 0;
        for (ClientResult result : results) {
            System.arraycopy(result.latencyNanos, 0, all, position, result.count);
            position += result.count;
            errors += result.errors;
            mismatches += result.mismatches;
        }
        Arrays.sort(all);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("client_concurrency", concurrency);
        summary.put("target_replicas", replicas);
        summary.put("successful_requests", total);
        summary.put("errors", errors);
        summary.put("mismatches", mismatches);
        summary.put("throughput_rps", total / (measureNanos / 1e9));
        summary.put("latency_mean_ms", total == 0 ? 0 : Arrays.stream(all).average().orElse(0) / 1e6);
        summary.put("latency_p50_ms", percentile(all, 0.50));
        summary.put("latency_p95_ms", percentile(all, 0.95));
        summary.put("latency_p99_ms", percentile(all, 0.99));
        summary.put("latency_max_ms", total == 0 ? 0 : all[total - 1] / 1e6);
        Files.writeString(out, Json.GSON.toJson(summary));
    }

    private static double percentile(long[] sorted, double fraction) {
        if (sorted.length == 0) {
            return 0;
        }
        return sorted[Math.min(sorted.length - 1, (int) Math.ceil(fraction * sorted.length) - 1)] / 1e6;
    }
}
