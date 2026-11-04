package com.calima.search.indexing;

import com.calima.search.common.Config;
import com.calima.search.common.HttpServers;
import com.calima.search.common.Json;
import com.calima.search.common.ServiceClient;
import com.calima.search.common.Sqlite;
import io.javalin.Javalin;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Indexing Service: turns available books into metadata and an inverted index, and publishes them. */
public final class App {

    private App() {
    }

    public static void main(String[] args) throws SQLException {
        Config config = Config.load();
        create(config).start(config.getInt("PORT", 7002));
    }

    /** Builds the server without starting it, so tests can run it on a free port. */
    public static Javalin create(Config config) throws SQLException {
        Sqlite db = Sqlite.writer(Path.of(config.get("DATAMART_PATH", "data/datamart/search.db")),
                config.getInt("SQLITE_POOL_SIZE", 8), config.getInt("SQLITE_BUSY_TIMEOUT_MS", 30_000),
                config.get("SQLITE_SYNCHRONOUS", "FULL"));
        int rebuildThreads = config.getInt("REBUILD_THREADS", 4);
        IndexingService service = new IndexingService(
                new DatamartRepository(db),
                new ServiceClient(config.getList("INGESTION_URLS", "http://localhost:7001"),
                        Duration.ofMillis(config.getInt("INTERNAL_HTTP_TIMEOUT_MS", 10_000))),
                new MetadataExtractor(MetadataExtractor.loadYearOverrides(config.get("YEAR_OVERRIDES_PATH", null))),
                Path.of(config.get("DATALAKE_PATH", "data/datalake")),
                rebuildThreads);

        Javalin app = HttpServers.create(config, "indexing-service", () -> Map.of("rebuild_threads", rebuildThreads));
        app.events(event -> event.serverStopped(db::close));
        app.post("/index/update/{book_id}", ctx -> {
            int bookId = HttpServers.bookId(ctx.pathParam("book_id"));
            boolean changed = service.update(bookId);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("book_id", bookId);
            body.put("index", changed ? "updated" : "unchanged");
            Json.respond(ctx, 200, body);
        });
        app.post("/index/rebuild", ctx -> {
            IndexingService.RebuildResult result = service.rebuild();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("books_processed", result.booksProcessed());
            body.put("elapsed_time", String.format(Locale.ROOT, "%.1fs", result.elapsedSeconds()));
            Json.respond(ctx, 200, body);
        });
        app.get("/index/status", ctx -> {
            DatamartRepository.Status status = service.status();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("books_indexed", status.booksIndexed());
            body.put("index_version", status.version());
            body.put("last_update", status.lastUpdateMillis() == null ? null
                    : Instant.ofEpochMilli(status.lastUpdateMillis()).toString());
            body.put("index_size_MB", Math.round(status.sizeBytes() / 104_857.6) / 10.0);
            Json.respond(ctx, 200, body);
        });
        app.get("/index/status/{book_id}", ctx -> {
            int bookId = HttpServers.bookId(ctx.pathParam("book_id"));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("book_id", bookId);
            body.put("status", service.isIndexed(bookId) ? "indexed" : "not_indexed");
            Json.respond(ctx, 200, body);
        });
        return app;
    }
}
