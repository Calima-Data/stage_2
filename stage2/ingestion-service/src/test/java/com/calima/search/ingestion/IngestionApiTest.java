package com.calima.search.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.calima.search.common.Config;
import com.calima.search.common.ServiceClient;
import com.calima.search.common.Sqlite;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import io.javalin.Javalin;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises the service over real HTTP, against a local stand-in for Project Gutenberg. */
class IngestionApiTest {

    private static final String BOOK = "Title: Alpha\n*** START OF THE PROJECT GUTENBERG EBOOK ALPHA ***\n"
            + "cuerpo del libro\n*** END OF THE PROJECT GUTENBERG EBOOK ALPHA ***\nfooter";

    @TempDir
    Path dir;

    private HttpServer gutenberg;
    private Javalin app;
    private ServiceClient client;
    private final AtomicInteger downloads = new AtomicInteger();
    private volatile CountDownLatch holdDownloads;
    private volatile int upstreamStatusFor500Book = 500;

    @BeforeEach
    void start() throws Exception {
        gutenberg = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        gutenberg.setExecutor(Executors.newCachedThreadPool());
        gutenberg.createContext("/", exchange -> {
            String id = exchange.getRequestURI().getPath().substring(1);
            downloads.incrementAndGet();
            if (holdDownloads != null) {
                try {
                    holdDownloads.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            int status = 200;
            String body = BOOK;
            if (id.equals("404")) {
                status = 404;
                body = "gone";
            } else if (id.equals("500")) {
                status = upstreamStatusFor500Book;
            } else if (id.equals("422")) {
                body = "this text has no markers";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        gutenberg.start();

        System.setProperty("DATALAKE_PATH", dir.resolve("lake").toString());
        System.setProperty("INGESTION_LEDGER_PATH", dir.resolve("state/ingestion.db").toString());
        System.setProperty("GUTENBERG_URL_TEMPLATE", "http://127.0.0.1:" + gutenberg.getAddress().getPort() + "/{id}");
        app = App.create(Config.load()).start(0);
        client = new ServiceClient(List.of("http://127.0.0.1:" + app.port()), Duration.ofSeconds(20));
    }

    @AfterEach
    void stop() {
        if (holdDownloads != null) {
            holdDownloads.countDown();
        }
        app.stop();
        gutenberg.stop(0);
    }

    private List<Path> filesOf(int bookId) throws Exception {
        try (Stream<Path> files = Files.walk(dir.resolve("lake"))) {
            return files.filter(p -> p.getFileName().toString().startsWith(bookId + ".")).sorted().toList();
        }
    }

    @Test
    void ingestStoresTheHeaderAndBodyInTheTimeBasedLayout() throws Exception {
        ServiceClient.Response response = client.post("/ingest/7");
        assertEquals(200, response.status());
        assertEquals("downloaded", response.json().get("status").getAsString());
        String path = response.json().get("path").getAsString();
        assertTrue(path.matches("datalake/\\d{8}/\\d{2}/7"), path);

        List<Path> files = filesOf(7);
        assertEquals(2, files.size(), files.toString());
        assertEquals("cuerpo del libro", Files.readString(files.get(0)));
        assertEquals("Title: Alpha", Files.readString(files.get(1)));
    }

    @Test
    void statusReportsAvailabilityWithSizesAndChecksum() throws Exception {
        assertEquals("not_ingested", client.get("/ingest/status/7").json().get("status").getAsString());
        client.post("/ingest/7");
        JsonObject status = client.get("/ingest/status/7").json();
        assertEquals("available", status.get("status").getAsString());
        assertEquals("cuerpo del libro".length(), status.get("body_bytes").getAsLong());
        assertEquals(64, status.get("body_sha256").getAsString().length());
    }

    @Test
    void repeatedIngestIsIdempotent() throws Exception {
        String firstPath = client.post("/ingest/7").json().get("path").getAsString();
        JsonObject second = client.post("/ingest/7").json();
        assertEquals("already_available", second.get("status").getAsString());
        assertEquals(firstPath, second.get("path").getAsString());
        assertEquals(1, downloads.get(), "the second call must not download again");
        assertEquals(1, client.get("/ingest/list").json().get("count").getAsInt());
    }

    @Test
    void concurrentIngestOfOneBookDownloadsItOnce() throws Exception {
        holdDownloads = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        List<Future<ServiceClient.Response>> calls = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            calls.add(executor.submit(() -> client.post("/ingest/7")));
        }
        Thread.sleep(500);
        holdDownloads.countDown();

        int ok = 0;
        int inProgress = 0;
        for (Future<ServiceClient.Response> call : calls) {
            ServiceClient.Response response = call.get();
            if (response.status() == 200) {
                ok++;
            } else if (response.status() == 409) {
                assertEquals("ingestion_in_progress", response.errorCode());
                inProgress++;
            }
        }
        executor.shutdown();
        assertEquals(8, ok + inProgress);
        assertEquals(1, downloads.get());
        assertEquals(2, filesOf(7).size());
    }

    @Test
    void errorsAreDocumentedAndLeaveNothingAvailable() throws Exception {
        ServiceClient.Response notFound = client.post("/ingest/404");
        assertEquals(404, notFound.status());
        assertEquals("book_not_found", notFound.errorCode());

        ServiceClient.Response invalid = client.post("/ingest/422");
        assertEquals(422, invalid.status());
        assertEquals("invalid_book_format", invalid.errorCode());

        ServiceClient.Response upstream = client.post("/ingest/500");
        assertEquals(502, upstream.status());
        assertEquals("upstream_unavailable", upstream.errorCode());

        assertEquals(400, client.post("/ingest/abc").status());
        assertEquals(400, client.post("/ingest/-5").status());
        assertEquals(0, client.get("/ingest/list").json().get("count").getAsInt());
        assertEquals("failed", client.get("/ingest/status/500").json().get("status").getAsString());
    }

    @Test
    void aFailedDownloadCanBeRetriedAtTheSamePath() throws Exception {
        assertEquals(502, client.post("/ingest/500").status());
        upstreamStatusFor500Book = 200;
        ServiceClient.Response retry = client.post("/ingest/500");
        assertEquals(200, retry.status());
        assertEquals("available", client.get("/ingest/status/500").json().get("status").getAsString());
    }

    @Test
    void unknownRouteHasAJsonError() throws Exception {
        ServiceClient.Response response = client.get("/nothing");
        assertEquals(404, response.status());
        assertEquals("route_not_found", response.errorCode());
    }

    // --- crash recovery, driven directly on the ledger ------------------------------------------

    @Test
    void anExpiredClaimIsTakenOverAndKeepsTheFirstPath() throws Exception {
        try (Sqlite db = Sqlite.writer(dir.resolve("ledger.db"), 2, 1000, "FULL")) {
            IngestionLedger ledger = new IngestionLedger(db, 1000);
            long t0 = Instant.parse("2031-01-01T10:00:00Z").toEpochMilli();

            IngestionLedger.Claim first = ledger.claim(9, "dead", "datalake/20310101/10/9", t0);
            assertEquals(IngestionLedger.ClaimResult.ACQUIRED, first.result());

            // While the lease is alive, another request is refused.
            assertEquals(IngestionLedger.ClaimResult.HELD_BY_ANOTHER,
                    ledger.claim(9, "other", "datalake/20310101/10/9", t0 + 500).result());

            // After it expires, a later attempt takes over, in another hour, at the first path.
            IngestionLedger.Claim takeover = ledger.claim(9, "new", "datalake/20310101/11/9", t0 + 3_600_000);
            assertEquals(IngestionLedger.ClaimResult.ACQUIRED, takeover.result());
            assertEquals("datalake/20310101/10/9", takeover.entry().path());

            // The request that lost its lease can no longer publish the book.
            BookStore.Stored stored = new BookStore.Stored(1, 1, "x");
            assertFalse(ledger.complete(9, "dead", stored, t0 + 3_600_001));
            assertTrue(ledger.complete(9, "new", stored, t0 + 3_600_001));
            assertEquals(List.of(9), ledger.listAvailable());
        }
    }

    @Test
    void writingTwiceLeavesOnePairAndNoTemporaries() throws Exception {
        BookStore store = new BookStore(dir.resolve("lake2"));
        GutenbergParser.Book book = new GutenbergParser.Book("h", "b");
        Path hourFolder = store.resolve("datalake/20310101/10/9").getParent();
        Files.createDirectories(hourFolder);
        Files.writeString(hourFolder.resolve("9.header.txt.tmp-crashed"), "partial");

        store.write("datalake/20310101/10/9", "r1", book);
        store.write("datalake/20310101/10/9", "r2", book);

        try (Stream<Path> files = Files.list(hourFolder)) {
            assertEquals(List.of("9.body.txt", "9.header.txt"),
                    files.map(p -> p.getFileName().toString()).sorted().toList());
        }
    }
}
