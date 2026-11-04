package com.calima.search.orchestrator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.calima.search.common.ApiException;
import com.calima.search.common.Config;
import com.calima.search.common.ServiceClient;
import com.calima.search.common.Sqlite;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Drives the real orchestrator and its API against stand-ins for the three services. */
class OrchestratorTest {

    @TempDir
    Path dir;

    private HttpServer services;
    private App.Running control;
    private ServiceClient client;
    private final HttpClient http = HttpClient.newHttpClient();
    private final List<String> calls = Collections.synchronizedList(new ArrayList<>());
    /** Responses to serve before the default 200, by "METHOD /path". */
    private final Map<String, List<Integer>> scripted = new ConcurrentHashMap<>();

    @BeforeEach
    void startServices() throws Exception {
        services = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        services.setExecutor(Executors.newCachedThreadPool());
        services.createContext("/", exchange -> {
            String call = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
            calls.add(call);
            int status = 200;
            String error = "";
            List<Integer> planned = scripted.get(call);
            if (planned != null && !planned.isEmpty()) {
                status = planned.remove(0);
                error = call.contains("/index/") && status == 409 ? "book_not_ready" : "scripted";
            }
            byte[] body = (status == 200 ? "{}" : "{\"error\":\"" + error + "\",\"message\":\"m\"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        services.start();
        String url = "http://127.0.0.1:" + services.getAddress().getPort();
        System.setProperty("INGESTION_URLS", url);
        System.setProperty("INDEXING_URLS", url);
        System.setProperty("SEARCH_URLS", url);
        System.setProperty("CONTROL_DB_PATH", dir.resolve("control.db").toString());
        System.setProperty("CONTROL_POLL_MS", "5");
        System.setProperty("RETRY_BASE_MS", "5");
        System.setProperty("RETRY_MAX_MS", "20");
        System.setProperty("MAX_ATTEMPTS", "4");
        System.setProperty("CONTROL_WORKERS", "2");
    }

    @AfterEach
    void stop() throws Exception {
        if (control != null) {
            control.orchestrator().stop();
            control.server().stop();
        }
        services.stop(0);
    }

    private void startControl() throws Exception {
        control = App.create(Config.load());
        control.server().start(0);
        control.orchestrator().start();
        client = new ServiceClient(List.of("http://127.0.0.1:" + control.server().port()), Duration.ofSeconds(10));
    }

    private int submit(String json) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + control.server().port() + "/control/books"))
                .POST(HttpRequest.BodyPublishers.ofString(json)).build();
        return http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    private void awaitState(int bookId, String state) throws Exception {
        long deadline = System.currentTimeMillis() + 15_000;
        JsonObject task = null;
        while (System.currentTimeMillis() < deadline) {
            ServiceClient.Response response = client.get("/control/books/" + bookId);
            task = response.json();
            if (response.status() == 200 && state.equals(task.get("state").getAsString())) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Book " + bookId + " never reached " + state + ": " + task);
    }

    private void script(String call, Integer... statuses) {
        scripted.put(call, Collections.synchronizedList(new ArrayList<>(List.of(statuses))));
    }

    private int count(String call) {
        return Collections.frequency(new ArrayList<>(calls), call);
    }

    @Test
    void aBookGoesThroughTheStepsInOrder() throws Exception {
        startControl();
        assertEquals(202, submit("{\"book_ids\":[7]}"));
        awaitState(7, TaskStore.PUBLISHED);
        assertEquals(List.of("POST /ingest/7", "POST /index/update/7", "GET /books/7"), new ArrayList<>(calls));
    }

    @Test
    void indexingNeverStartsBeforeIngestionSucceeded() throws Exception {
        script("POST /ingest/7", 503, 502);
        startControl();
        submit("{\"book_ids\":[7]}");
        awaitState(7, TaskStore.PUBLISHED);
        List<String> seen = new ArrayList<>(calls);
        assertEquals(3, count("POST /ingest/7"));
        assertEquals(1, count("POST /index/update/7"));
        assertTrue(seen.lastIndexOf("POST /ingest/7") < seen.indexOf("POST /index/update/7"));
        assertEquals(2, client.get("/control/books/7").json().get("total_retries").getAsInt());
    }

    @Test
    void permanentErrorsFailWithoutRetrying() throws Exception {
        script("POST /ingest/404", 404);
        script("POST /ingest/422", 422);
        startControl();
        submit("{\"book_ids\":[404,422]}");
        awaitState(404, TaskStore.FAILED);
        awaitState(422, TaskStore.FAILED);
        assertEquals(1, count("POST /ingest/404"));
        assertEquals(0, count("POST /index/update/404"));
        assertTrue(client.get("/control/books/404").json().get("last_error").getAsString().contains("404"));
    }

    @Test
    void attemptsAreBoundedAndAFailedBookCanBeRetried() throws Exception {
        script("POST /index/update/7", 503, 503, 503, 503);
        startControl();
        submit("{\"book_ids\":[7]}");
        awaitState(7, TaskStore.FAILED);
        assertEquals(4, count("POST /index/update/7"));

        ServiceClient.Response retry = client.post("/control/books/7/retry");
        assertEquals(202, retry.status());
        awaitState(7, TaskStore.PUBLISHED);
        assertEquals(409, client.post("/control/books/7/retry").status());
    }

    @Test
    void aBookThatIsNoLongerReadyRewindsToIngestion() throws Exception {
        script("POST /index/update/7", 409);
        startControl();
        submit("{\"book_ids\":[7]}");
        awaitState(7, TaskStore.PUBLISHED);
        assertEquals(2, count("POST /ingest/7"));
        assertEquals(2, count("POST /index/update/7"));
    }

    @Test
    void publicationIsOnlyRecordedOnceSearchServesTheBook() throws Exception {
        script("GET /books/7", 404, 503);
        startControl();
        submit("{\"book_ids\":[7]}");
        awaitState(7, TaskStore.PUBLISHED);
        assertEquals(3, count("GET /books/7"));
        assertEquals(1, count("POST /index/update/7"), "retrying the check must not repeat indexing");
    }

    @Test
    void submissionIsIdempotentAndValidated() throws Exception {
        startControl();
        submit("{\"from\":1,\"to\":3}");
        awaitState(3, TaskStore.PUBLISHED);
        awaitState(1, TaskStore.PUBLISHED);
        awaitState(2, TaskStore.PUBLISHED);
        int callsBefore = calls.size();
        assertEquals(202, submit("{\"book_ids\":[1,2,3]}"));
        Thread.sleep(100);
        assertEquals(callsBefore, calls.size(), "known books must not run again");

        assertEquals(400, submit("not json"));
        assertEquals(400, submit("{}"));
        assertEquals(400, submit("{\"book_ids\":[]}"));
        assertEquals(400, submit("{\"book_ids\":[-1]}"));
        assertEquals(400, submit("{\"book_ids\":[\"x\"]}"));
        assertEquals(400, submit("{\"from\":5,\"to\":1}"));
        assertEquals(404, client.get("/control/books/999").status());
    }

    @Test
    void statusReportsCountsAndTimings() throws Exception {
        startControl();
        submit("{\"book_ids\":[1,2]}");
        awaitState(1, TaskStore.PUBLISHED);
        awaitState(2, TaskStore.PUBLISHED);
        JsonObject status = client.get("/control/status").json();
        assertEquals(2, status.getAsJsonObject("states").get(TaskStore.PUBLISHED).getAsInt());
        assertEquals(0, status.getAsJsonObject("states").get(TaskStore.PENDING).getAsInt());
        JsonObject metrics = status.getAsJsonObject("published_tasks");
        assertEquals(2, metrics.get("count").getAsInt());
        assertTrue(metrics.get("avg_coordination_ms").getAsDouble() > 0);
    }

    // --- task table, without threads ------------------------------------------------------------

    private Sqlite storeDb;

    private TaskStore store(long leaseMs) throws Exception {
        storeDb = Sqlite.writer(dir.resolve("store.db"), 8, 5000, "FULL");
        return new TaskStore(storeDb, leaseMs);
    }

    @AfterEach
    void closeStore() {
        if (storeDb != null) {
            storeDb.close();
        }
    }

    @Test
    void workInProgressIsClaimedBeforeNewBooks() throws Exception {
        TaskStore store = store(1000);
        store.enqueue(List.of(1, 2, 3), 0);
        assertEquals(1, store.claimNext("a", 10).bookId());
        assertEquals(2, store.claimNext("b", 10).bookId());
        store.advance(1, "a", TaskStore.INGESTED, new TaskStore.StepTiming("ingest_ms", 1, 0), 11);
        store.advance(2, "b", TaskStore.INGESTED, new TaskStore.StepTiming("ingest_ms", 1, 0), 11);

        // Book 2 is closer to the end of its work after this step than the untouched book 3.
        assertEquals(1, store.claimNext("a", 12).bookId());
        store.advance(1, "a", TaskStore.INDEXED, new TaskStore.StepTiming("index_ms", 1, 0), 13);

        // Most advanced first: book 1 (INDEXED), then book 2 (INGESTED), and only then new book 3.
        assertEquals(1, store.claimNext("a", 14).bookId());
        assertEquals(2, store.claimNext("b", 14).bookId());
        assertEquals(3, store.claimNext("c", 14).bookId());
    }

    @Test
    void aClaimedTaskCannotBeClaimedAgainUntilItsLeaseExpires() throws Exception {
        TaskStore store = store(1000);
        store.enqueue(List.of(1), 0);
        assertNotNull(store.claimNext("a", 10));
        assertNull(store.claimNext("b", 500));
        TaskStore.Task afterExpiry = store.claimNext("b", 1011);
        assertEquals(1, afterExpiry.bookId());

        // The worker that lost the claim can no longer record anything.
        store.advance(1, "a", TaskStore.INGESTED, new TaskStore.StepTiming("ingest_ms", 1, 0), 1012);
        assertEquals(TaskStore.PENDING, store.describe(1).get("state"));
    }

    @Test
    void releasingClaimsAtStartupResumesInterruptedWork() throws Exception {
        TaskStore store = store(1_000_000);
        store.enqueue(List.of(1), 0);
        assertNotNull(store.claimNext("dead", 10));
        assertNull(store.claimNext("new", 20));
        assertEquals(1, store.releaseAllClaims());
        assertEquals(1, store.claimNext("new", 20).bookId());
    }

    @Test
    void concurrentClaimsNeverHandOutOneTaskTwice() throws Exception {
        TaskStore store = store(1_000_000);
        List<Integer> ids = new ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            ids.add(i);
        }
        store.enqueue(ids, 0);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        List<Future<List<Integer>>> workers = new ArrayList<>();
        AtomicInteger names = new AtomicInteger();
        for (int w = 0; w < 8; w++) {
            workers.add(executor.submit(() -> {
                String owner = "w" + names.incrementAndGet();
                List<Integer> claimed = new ArrayList<>();
                TaskStore.Task task;
                while ((task = store.claimNext(owner, 10)) != null) {
                    claimed.add(task.bookId());
                }
                return claimed;
            }));
        }
        List<Integer> all = new ArrayList<>();
        for (Future<List<Integer>> worker : workers) {
            all.addAll(worker.get());
        }
        executor.shutdown();
        Collections.sort(all);
        assertEquals(ids, all);
    }

    @Test
    void readyTasksRespectTheirRetryTime() throws Exception {
        TaskStore store = store(1000);
        store.enqueue(List.of(1), 0);
        store.claimNext("a", 10);
        store.retry(1, "a", TaskStore.PENDING, "boom", 100);
        assertNull(store.claimNext("a", 50));
        assertEquals(1, store.claimNext("a", 100).bookId());
    }

    @Test
    void theRangeSyntaxIsExpandedAndLimited() {
        assertEquals(List.of(2, 3, 4), App.parseBookIds("{\"from\":2,\"to\":4}"));
        assertEquals(List.of(9, 8), App.parseBookIds("{\"book_ids\":[9,8]}"));
        assertThrows(ApiException.class, () -> App.parseBookIds("{\"from\":1,\"to\":100001}"));
        assertFalse(App.parseBookIds("{\"from\":1,\"to\":100000}").isEmpty());
    }
}
