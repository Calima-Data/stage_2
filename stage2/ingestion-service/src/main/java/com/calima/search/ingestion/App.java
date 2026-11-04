package com.calima.search.ingestion;

import com.calima.search.common.Config;
import com.calima.search.common.HttpServers;
import com.calima.search.common.Json;
import com.calima.search.common.Sqlite;
import io.javalin.Javalin;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Ingestion Service: downloads books into the datalake and records which ones are ready. */
public final class App {

    private App() {
    }

    public static void main(String[] args) throws SQLException {
        Config config = Config.load();
        create(config).start(config.getInt("PORT", 7001));
    }

    /** Builds the server without starting it, so tests can run it on a free port. */
    public static Javalin create(Config config) throws SQLException {
        Path datalake = Path.of(config.get("DATALAKE_PATH", "data/datalake"));
        Path ledgerPath = Path.of(config.get("INGESTION_LEDGER_PATH", "data/state/ingestion.db"));
        Sqlite db = Sqlite.writer(ledgerPath, config.getInt("SQLITE_POOL_SIZE", 8),
                config.getInt("SQLITE_BUSY_TIMEOUT_MS", 10_000), config.get("SQLITE_SYNCHRONOUS", "FULL"));
        IngestionLedger ledger = new IngestionLedger(db, config.getInt("INGEST_LEASE_MS", 120_000));
        GutenbergClient gutenberg = new GutenbergClient(
                config.get("GUTENBERG_URL_TEMPLATE", "https://www.gutenberg.org/cache/epub/{id}/pg{id}.txt"),
                Duration.ofMillis(config.getInt("DOWNLOAD_TIMEOUT_MS", 30_000)));
        IngestionService service = new IngestionService(ledger, new BookStore(datalake), gutenberg);

        Javalin app = HttpServers.create(config, "ingestion-service", () -> Map.of("datalake_layout", "time_based"));
        app.events(event -> event.serverStopped(db::close));
        app.post("/ingest/{book_id}", ctx -> {
            IngestionService.Outcome outcome = service.ingest(HttpServers.bookId(ctx.pathParam("book_id")));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("book_id", outcome.bookId());
            body.put("status", outcome.status());
            body.put("path", outcome.path());
            Json.respond(ctx, 200, body);
        });
        app.get("/ingest/status/{book_id}", ctx -> {
            int bookId = HttpServers.bookId(ctx.pathParam("book_id"));
            Json.respond(ctx, 200, statusBody(bookId, service.status(bookId)));
        });
        app.get("/ingest/list", ctx -> {
            List<Integer> books = service.listAvailable();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("count", books.size());
            body.put("books", books);
            Json.respond(ctx, 200, body);
        });
        return app;
    }

    private static Map<String, Object> statusBody(int bookId, IngestionLedger.Entry entry) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("book_id", bookId);
        if (entry == null) {
            body.put("status", "not_ingested");
            return body;
        }
        body.put("status", entry.state());
        if (IngestionLedger.AVAILABLE.equals(entry.state())) {
            body.put("path", entry.path());
            body.put("header_bytes", entry.headerBytes());
            body.put("body_bytes", entry.bodyBytes());
            body.put("body_sha256", entry.bodySha256());
        } else if (IngestionLedger.FAILED.equals(entry.state())) {
            body.put("last_error", entry.lastError());
        }
        return body;
    }
}
