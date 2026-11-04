package com.calima.search.query;

import com.calima.search.common.ApiException;
import com.calima.search.common.Config;
import com.calima.search.common.HttpServers;
import com.calima.search.common.Json;
import com.calima.search.common.Sqlite;
import io.javalin.Javalin;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Search Service: the front-end-facing API over the published datamart, which it only reads. */
public final class App {

    private App() {
    }

    public static void main(String[] args) {
        Config config = Config.load();
        create(config).start(config.getInt("PORT", 7003));
    }

    /** Builds the server without starting it, so tests can run it on a free port. */
    public static Javalin create(Config config) {
        int poolSize = config.getInt("SQLITE_POOL_SIZE", 16);
        int cacheSize = config.getInt("SEARCH_CACHE_SIZE", 1000);
        Sqlite db = Sqlite.reader(Path.of(config.get("DATAMART_PATH", "data/datamart/search.db")), poolSize,
                config.getInt("SQLITE_BUSY_TIMEOUT_MS", 5_000));
        SearchEngine engine = new SearchEngine(db, new ResultCache(cacheSize));

        Javalin app = HttpServers.create(config, "search-service",
                () -> Map.of("db_read_connections", poolSize, "cache_entries", cacheSize));
        app.events(event -> event.serverStopped(db::close));

        app.get("/search", ctx -> {
            SearchRequest request = SearchRequest.parse(ctx.queryParamMap());
            SearchEngine.Answer answer = engine.search(request);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("query", request.query());
            body.put("filters", request.filters());
            body.put("total", answer.total());
            body.put("offset", request.offset());
            body.put("limit", request.limit());
            body.put("results", answer.results());
            ctx.header("X-Index-Version", Long.toString(answer.version()));
            Json.respond(ctx, 200, body);
        });
        app.get("/search/filters", ctx -> Json.respond(ctx, 200, engine.filters()));
        app.get("/books/{book_id}", ctx -> {
            int bookId = HttpServers.bookId(ctx.pathParam("book_id"));
            Map<String, Object> book = engine.book(bookId);
            if (book == null) {
                throw new ApiException(404, "book_not_found", "Book " + bookId + " is not published");
            }
            Json.respond(ctx, 200, book);
        });
        return app;
    }
}
