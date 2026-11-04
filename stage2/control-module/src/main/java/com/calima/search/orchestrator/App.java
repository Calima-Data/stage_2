package com.calima.search.orchestrator;

import com.calima.search.common.ApiException;
import com.calima.search.common.Config;
import com.calima.search.common.HttpServers;
import com.calima.search.common.Json;
import com.calima.search.common.ServiceClient;
import com.calima.search.common.Sqlite;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.javalin.Javalin;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Control Module: the orchestrator of the pipeline, and its administrative API. */
public final class App {

    private static final int MAX_BOOKS_PER_REQUEST = 100_000;

    /** The running module, so tests can stop its workers. */
    public record Running(Javalin server, Orchestrator orchestrator) {
    }

    private App() {
    }

    public static void main(String[] args) throws SQLException {
        Config config = Config.load();
        Running running = create(config);
        running.server().start(config.getInt("PORT", 7000));
        running.orchestrator().start();
    }

    /** Builds the module without starting it, so tests can run it on a free port. */
    public static Running create(Config config) throws SQLException {
        Sqlite db = Sqlite.writer(Path.of(config.get("CONTROL_DB_PATH", "data/state/control.db")), 16,
                config.getInt("SQLITE_BUSY_TIMEOUT_MS", 10_000), config.get("SQLITE_SYNCHRONOUS", "FULL"));
        TaskStore store = new TaskStore(db, config.getInt("TASK_LEASE_MS", 180_000));
        store.releaseAllClaims();

        int workers = config.getInt("CONTROL_WORKERS", 4);
        Orchestrator orchestrator = new Orchestrator(store,
                client(config, "INGESTION_URLS", "http://localhost:7001", "INGEST_TIMEOUT_MS", 60_000),
                client(config, "INDEXING_URLS", "http://localhost:7002", "INDEX_TIMEOUT_MS", 60_000),
                client(config, "SEARCH_URLS", "http://localhost:7003", "PUBLISH_CHECK_TIMEOUT_MS", 5_000),
                new Orchestrator.Settings(workers, config.getInt("CONTROL_POLL_MS", 50), config.getInt("MAX_ATTEMPTS", 8),
                        config.getInt("RETRY_BASE_MS", 500), config.getInt("RETRY_MAX_MS", 30_000)));

        Javalin app = HttpServers.create(config, "control-module", () -> Map.of("workers", workers));
        app.events(event -> event.serverStopped(db::close));
        app.post("/control/books", ctx -> {
            List<Integer> ids = parseBookIds(ctx.body());
            int added = store.enqueue(ids, System.currentTimeMillis());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("requested", ids.size());
            body.put("enqueued", added);
            body.put("already_known", ids.size() - added);
            Json.respond(ctx, 202, body);
        });
        app.get("/control/books/{book_id}", ctx -> {
            int bookId = HttpServers.bookId(ctx.pathParam("book_id"));
            Map<String, Object> task = store.describe(bookId);
            if (task == null) {
                throw new ApiException(404, "task_not_found", "Book " + bookId + " was never submitted");
            }
            Json.respond(ctx, 200, task);
        });
        app.post("/control/books/{book_id}/retry", ctx -> {
            int bookId = HttpServers.bookId(ctx.pathParam("book_id"));
            if (!store.requeueFailed(bookId)) {
                throw new ApiException(409, "task_not_failed", "Book " + bookId + " is not in state FAILED");
            }
            Json.respond(ctx, 202, Map.of("book_id", bookId, "state", TaskStore.PENDING));
        });
        app.get("/control/status", ctx -> Json.respond(ctx, 200, store.summary()));
        return new Running(app, orchestrator);
    }

    private static ServiceClient client(Config config, String urls, String defaultUrl, String timeout, int defaultTimeout) {
        return new ServiceClient(config.getList(urls, defaultUrl), Duration.ofMillis(config.getInt(timeout, defaultTimeout)));
    }

    /** Accepts {@code {"book_ids":[1,2]}} or {@code {"from":1,"to":100}}. */
    static List<Integer> parseBookIds(String raw) {
        JsonObject body;
        try {
            JsonElement element = JsonParser.parseString(raw);
            if (!element.isJsonObject()) {
                throw invalidBody();
            }
            body = element.getAsJsonObject();
        } catch (RuntimeException e) {
            throw invalidBody();
        }
        List<Integer> ids = new ArrayList<>();
        try {
            if (body.has("book_ids")) {
                JsonArray array = body.getAsJsonArray("book_ids");
                array.forEach(id -> ids.add(positive(id.getAsInt())));
            } else if (body.has("from") && body.has("to")) {
                int from = positive(body.get("from").getAsInt());
                int to = positive(body.get("to").getAsInt());
                if (to < from || (long) to - from >= MAX_BOOKS_PER_REQUEST) {
                    throw invalidBody();
                }
                for (int id = from; id <= to; id++) {
                    ids.add(id);
                }
            } else {
                throw invalidBody();
            }
        } catch (ClassCastException | NumberFormatException | IllegalStateException e) {
            throw invalidBody();
        }
        if (ids.isEmpty() || ids.size() > MAX_BOOKS_PER_REQUEST) {
            throw invalidBody();
        }
        return ids;
    }

    private static int positive(int id) {
        if (id <= 0) {
            throw invalidBody();
        }
        return id;
    }

    private static ApiException invalidBody() {
        return new ApiException(400, "invalid_body", "Expected {\"book_ids\":[1,2,...]} or {\"from\":1,\"to\":100} "
                + "with positive IDs and at most " + MAX_BOOKS_PER_REQUEST + " books");
    }
}
