package com.calima.search.common;

import io.javalin.Javalin;
import java.lang.management.ManagementFactory;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Builds a Javalin server with the conventions every service shares. */
public final class HttpServers {

    /** Threads Jetty keeps for acceptors and selectors, on top of the request threads. */
    private static final int JETTY_IO_THREADS = 8;
    /** The pool Javalin creates when HTTP_THREADS is not set. */
    private static final int JAVALIN_DEFAULT_MAX_THREADS = 250;
    private static final String HANDLED_BY_API = "handled-by-api";

    private static final Logger LOG = LoggerFactory.getLogger(HttpServers.class);

    private HttpServers() {
    }

    /**
     * Creates the server and registers error handling and {@code GET /status}.
     *
     * @param extraStatus service-specific fields for the status response
     */
    public static Javalin create(Config config, String service, Supplier<Map<String, Object>> extraStatus) {
        int httpThreads = config.getInt("HTTP_THREADS", 0);
        List<String> allowedOrigins = config.getList("CORS_ALLOWED_ORIGINS", "");

        Javalin app = Javalin.create(javalin -> {
            javalin.showJavalinBanner = false;
            javalin.http.defaultContentType = "application/json";
            if (httpThreads > 0) {
                javalin.jetty.threadPool = new QueuedThreadPool(httpThreads + JETTY_IO_THREADS,
                        Math.min(8, httpThreads + JETTY_IO_THREADS), 60_000);
            }
            if (!allowedOrigins.isEmpty()) {
                javalin.bundledPlugins.enableCors(cors -> cors.addRule(rule -> {
                    allowedOrigins.forEach(rule::allowHost);
                }));
            }
        });

        app.exception(ApiException.class, (e, ctx) -> {
            if (e.retryAfterSeconds() != null) {
                ctx.header("Retry-After", String.valueOf(e.retryAfterSeconds()));
            }
            ctx.attribute(HANDLED_BY_API, true);
            Json.respond(ctx, e.status(), Map.of("error", e.code(), "message", e.getMessage()));
        });
        app.exception(SQLException.class, (e, ctx) -> {
            if (Sqlite.isBusy(e)) {
                ctx.header("Retry-After", "1");
                Json.respond(ctx, 503, Map.of("error", "database_busy", "message", "Another writer holds the database lock"));
            } else {
                LOG.error("Database error on {} {}", ctx.method(), ctx.path(), e);
                Json.respond(ctx, 500, Map.of("error", "internal_error", "message", "Unexpected database error"));
            }
        });
        app.exception(Exception.class, (e, ctx) -> {
            LOG.error("Unhandled error on {} {}", ctx.method(), ctx.path(), e);
            Json.respond(ctx, 500, Map.of("error", "internal_error", "message", "Unexpected server error"));
        });
        // Javalin also runs this for the 404s of ApiException, which must keep their own body.
        app.error(404, ctx -> {
            if (ctx.attribute(HANDLED_BY_API) == null) {
                Json.respond(ctx, 404, Map.of("error", "route_not_found", "message", "No such route: " + ctx.path()));
            }
        });

        String instance = ManagementFactory.getRuntimeMXBean().getName();
        app.get("/status", ctx -> {
            Map<String, Object> status = new LinkedHashMap<>();
            status.put("service", service);
            status.put("status", "running");
            status.put("instance", instance);
            status.put("http_threads", httpThreads > 0 ? httpThreads
                    : "framework default (Jetty pool of at most " + JAVALIN_DEFAULT_MAX_THREADS + ")");
            status.putAll(extraStatus.get());
            Json.respond(ctx, 200, status);
        });
        return app;
    }

    /** Parses a {@code book_id} path parameter: a positive integer, or a documented 400. */
    public static int bookId(String raw) {
        try {
            int id = Integer.parseInt(raw);
            if (id > 0) {
                return id;
            }
        } catch (NumberFormatException e) {
            // reported below
        }
        throw new ApiException(400, "invalid_book_id", "book_id must be a positive integer, got: " + raw);
    }
}
