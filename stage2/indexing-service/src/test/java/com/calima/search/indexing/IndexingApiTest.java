package com.calima.search.indexing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.calima.search.common.Config;
import com.calima.search.common.ServiceClient;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import io.javalin.Javalin;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Runs the service over real HTTP with a stand-in Ingestion Service and a real datalake folder. */
class IndexingApiTest {

    @TempDir
    Path dir;

    private final Map<Integer, String> ingestionAnswers = new ConcurrentHashMap<>();
    private HttpServer ingestion;
    private Javalin app;
    private ServiceClient client;
    private Path datamart;
    private volatile boolean ingestionFailing;

    @BeforeEach
    void start() throws Exception {
        ingestion = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ingestion.setExecutor(Executors.newCachedThreadPool());
        ingestion.createContext("/ingest/status/", exchange -> {
            int id = Integer.parseInt(exchange.getRequestURI().getPath().substring("/ingest/status/".length()));
            String answer = ingestionAnswers.getOrDefault(id, "{\"book_id\":" + id + ",\"status\":\"not_ingested\"}");
            respond(exchange, ingestionFailing ? 500 : 200, answer);
        });
        ingestion.createContext("/ingest/list", exchange -> {
            List<Integer> ids = new ArrayList<>(ingestionAnswers.keySet());
            respond(exchange, 200, "{\"count\":" + ids.size() + ",\"books\":" + ids + "}");
        });
        ingestion.start();

        datamart = dir.resolve("mart/search.db");
        System.setProperty("DATALAKE_PATH", dir.resolve("lake").toString());
        System.setProperty("DATAMART_PATH", datamart.toString());
        System.setProperty("INGESTION_URLS", "http://127.0.0.1:" + ingestion.getAddress().getPort());
        app = App.create(Config.load()).start(0);
        client = new ServiceClient(List.of("http://127.0.0.1:" + app.port()), Duration.ofSeconds(30));
    }

    @AfterEach
    void stop() {
        app.stop();
        ingestion.stop(0);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    /** Places a book in the datalake and makes the stand-in report it as available. */
    private void ingested(int id, String title, String author, String body) throws Exception {
        Path folder = dir.resolve("lake/20310101/10");
        Files.createDirectories(folder);
        Files.writeString(folder.resolve(id + ".header.txt"), "Title: " + title + "\nAuthor: " + author + "\nLanguage: English\n");
        Files.writeString(folder.resolve(id + ".body.txt"), body);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
        ingestionAnswers.put(id, "{\"book_id\":" + id + ",\"status\":\"available\",\"path\":\"datalake/20310101/10/" + id
                + "\",\"body_sha256\":\"" + sha + "\"}");
    }

    private Map<String, Integer> postingsOf(int bookId) throws Exception {
        Map<String, Integer> postings = new TreeMap<>();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + datamart);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT term, tf FROM postings WHERE book_id = " + bookId)) {
            while (rs.next()) {
                postings.put(rs.getString(1), rs.getInt(2));
            }
        }
        return postings;
    }

    private long version() throws Exception {
        return client.get("/index/status").json().get("index_version").getAsLong();
    }

    @Test
    void updateStoresPostingsAndMetadataAtomically() throws Exception {
        ingested(1, "Alpha", "Ann Author", "The whale and the sea");
        ServiceClient.Response response = client.post("/index/update/1");
        assertEquals(200, response.status());
        assertEquals("updated", response.json().get("index").getAsString());

        assertEquals(Map.of("the", 2, "whale", 1, "and", 1, "sea", 1), postingsOf(1));
        JsonObject status = client.get("/index/status").json();
        assertEquals(1, status.get("books_indexed").getAsInt());
        assertEquals(1, status.get("index_version").getAsLong());
        assertTrue(status.get("index_size_MB").getAsDouble() >= 0);
        assertEquals("indexed", client.get("/index/status/1").json().get("status").getAsString());
        assertEquals("not_indexed", client.get("/index/status/2").json().get("status").getAsString());
    }

    @Test
    void aBookThatHasNotCompletedIngestionIsRefused() throws Exception {
        ServiceClient.Response response = client.post("/index/update/5");
        assertEquals(409, response.status());
        assertEquals("book_not_ready", response.errorCode());

        ingestionAnswers.put(5, "{\"book_id\":5,\"status\":\"in_progress\"}");
        assertEquals(409, client.post("/index/update/5").status());
        assertEquals(0, version());
    }

    @Test
    void anUnavailableIngestionServiceIsARetryableError() throws Exception {
        ingestionFailing = true;
        ServiceClient.Response response = client.post("/index/update/5");
        assertEquals(503, response.status());
        assertEquals("ingestion_unavailable", response.errorCode());
    }

    @Test
    void repeatingAnUpdateChangesNothing() throws Exception {
        ingested(1, "Alpha", "A", "whale sea");
        client.post("/index/update/1");
        long version = version();
        Map<String, Integer> postings = postingsOf(1);

        ServiceClient.Response second = client.post("/index/update/1");
        assertEquals(200, second.status());
        assertEquals("unchanged", second.json().get("index").getAsString());
        assertEquals(version, version());
        assertEquals(postings, postingsOf(1));
    }

    @Test
    void changedContentReplacesThePreviousPostings() throws Exception {
        ingested(1, "Alpha", "A", "whale sea");
        client.post("/index/update/1");
        ingested(1, "Alpha", "A", "harbour");
        assertEquals("updated", client.post("/index/update/1").json().get("index").getAsString());
        assertEquals(Map.of("harbour", 1), postingsOf(1));
    }

    @Test
    void aBookThatDoesNotMatchItsChecksumIsNotPublished() throws Exception {
        ingested(1, "Alpha", "A", "whale sea");
        Files.writeString(dir.resolve("lake/20310101/10/1.body.txt"), "tampered");
        ServiceClient.Response response = client.post("/index/update/1");
        assertEquals(500, response.status());
        assertEquals("datalake_inconsistent", response.errorCode());
        assertEquals(0, version());
    }

    @Test
    void concurrentUpdatesKeepEveryBookWithoutDuplicates() throws Exception {
        int books = 20;
        for (int id = 1; id <= books; id++) {
            ingested(id, "Book " + id, "A", "common word" + id);
        }
        ExecutorService executor = Executors.newFixedThreadPool(8);
        List<Future<ServiceClient.Response>> calls = new ArrayList<>();
        for (int repeat = 0; repeat < 3; repeat++) {
            for (int id = 1; id <= books; id++) {
                String path = "/index/update/" + id;
                calls.add(executor.submit(() -> client.post(path)));
            }
        }
        int updated = 0;
        for (Future<ServiceClient.Response> call : calls) {
            ServiceClient.Response response = call.get();
            assertEquals(200, response.status(), String.valueOf(response.json()));
            if ("updated".equals(response.json().get("index").getAsString())) {
                updated++;
            }
        }
        executor.shutdown();
        assertEquals(books, updated, "each book must be published exactly once");
        assertEquals(books, client.get("/index/status").json().get("books_indexed").getAsInt());
        assertEquals(books, version());
        for (int id = 1; id <= books; id++) {
            assertEquals(Map.of("common", 1, "word" + id, 1), postingsOf(id));
        }
    }

    @Test
    void rebuildProducesTheSameIndexAsIncrementalUpdates() throws Exception {
        for (int id = 1; id <= 5; id++) {
            ingested(id, "Book " + id, "A", "shared term" + id + " shared");
            client.post("/index/update/" + id);
        }
        Map<Integer, Map<String, Integer>> incremental = new TreeMap<>();
        for (int id = 1; id <= 5; id++) {
            incremental.put(id, postingsOf(id));
        }
        ServiceClient.Response rebuild = client.post("/index/rebuild");
        assertEquals(200, rebuild.status());
        assertEquals(5, rebuild.json().get("books_processed").getAsInt());
        assertTrue(rebuild.json().get("elapsed_time").getAsString().endsWith("s"));
        for (int id = 1; id <= 5; id++) {
            assertEquals(incremental.get(id), postingsOf(id));
        }
    }

    @Test
    void aFailedRebuildLeavesThePublishedIndexIntact() throws Exception {
        ingested(1, "Alpha", "A", "whale");
        ingested(2, "Beta", "A", "ship");
        client.post("/index/update/1");
        client.post("/index/update/2");
        long version = version();

        Files.delete(dir.resolve("lake/20310101/10/2.body.txt"));
        ServiceClient.Response rebuild = client.post("/index/rebuild");
        assertEquals(500, rebuild.status());
        assertEquals("datalake_inconsistent", rebuild.errorCode());
        assertEquals(version, version());
        assertEquals(Map.of("whale", 1), postingsOf(1));
        assertEquals(Map.of("ship", 1), postingsOf(2));
    }

    @Test
    void statusOfAnEmptyIndexHasNoLastUpdate() throws Exception {
        JsonObject status = client.get("/index/status").json();
        assertEquals(0, status.get("books_indexed").getAsInt());
        assertTrue(status.get("last_update").isJsonNull());
        assertNull(client.get("/index/status").errorCode());
    }
}
