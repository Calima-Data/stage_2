package com.calima.search.indexing;

import com.calima.search.common.ApiException;
import com.calima.search.common.ServiceClient;
import com.calima.search.common.Tokenizer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Indexes books from the datalake. Parsing runs outside any database lock, so it can use many
 * threads and replicas; only the final write is serialized, by the datamart's write transaction.
 */
public final class IndexingService {

    private static final int RETRY_AFTER_SECONDS = 2;

    public record RebuildResult(int booksProcessed, double elapsedSeconds) {
    }

    private final DatamartRepository repository;
    private final ServiceClient ingestion;
    private final MetadataExtractor extractor;
    private final Path datalake;
    private final int rebuildThreads;
    private final AtomicBoolean rebuilding = new AtomicBoolean();

    public IndexingService(DatamartRepository repository, ServiceClient ingestion, MetadataExtractor extractor,
                           Path datalake, int rebuildThreads) {
        this.repository = repository;
        this.ingestion = ingestion;
        this.extractor = extractor;
        this.datalake = datalake;
        this.rebuildThreads = rebuildThreads;
    }

    /** Indexes one book and returns true if the datamart changed, false if it was already up to date. */
    public boolean update(int bookId) throws SQLException, InterruptedException {
        return repository.publish(parse(bookId, readyLocation(bookId)));
    }

    /**
     * Rebuilds everything from the books the Ingestion Service reports as available. A concurrent
     * rebuild in this process is refused at once; one in another replica is stopped by the
     * datamart's write lock.
     */
    public RebuildResult rebuild() throws SQLException, InterruptedException {
        if (!rebuilding.compareAndSet(false, true)) {
            throw new ApiException(409, "rebuild_in_progress", "A rebuild is already running", RETRY_AFTER_SECONDS);
        }
        long start = System.nanoTime();
        try {
            List<Integer> ids = availableBooks();
            List<JsonObject> locations = new ArrayList<>(ids.size());
            for (int id : ids) {
                locations.add(readyLocation(id));
            }
            ExecutorService parsers = Executors.newFixedThreadPool(rebuildThreads);
            try {
                repository.rebuild(sink -> {
                    // Windows of books bound the memory held by parsed postings.
                    int window = rebuildThreads * 2;
                    for (int from = 0; from < ids.size(); from += window) {
                        List<Future<DatamartRepository.BookIndex>> parsed = new ArrayList<>();
                        for (int i = from; i < Math.min(from + window, ids.size()); i++) {
                            int bookId = ids.get(i);
                            JsonObject location = locations.get(i);
                            parsed.add(parsers.submit(() -> parse(bookId, location)));
                        }
                        for (Future<DatamartRepository.BookIndex> book : parsed) {
                            sink.accept(unwrap(book));
                        }
                    }
                });
            } finally {
                parsers.shutdownNow();
            }
            return new RebuildResult(ids.size(), (System.nanoTime() - start) / 1e9);
        } finally {
            rebuilding.set(false);
        }
    }

    public boolean isIndexed(int bookId) throws SQLException {
        return repository.isIndexed(bookId);
    }

    public DatamartRepository.Status status() throws SQLException {
        return repository.status();
    }

    private static DatamartRepository.BookIndex unwrap(Future<DatamartRepository.BookIndex> future)
            throws InterruptedException {
        try {
            return future.get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof ApiException api) {
                throw api;
            }
            throw new IllegalStateException(e.getCause());
        }
    }

    /** Asks the Ingestion Service: only a book it reports as available may be indexed. */
    private JsonObject readyLocation(int bookId) throws InterruptedException {
        ServiceClient.Response response = callIngestion("/ingest/status/" + bookId);
        if (response.status() != 200 || response.json() == null) {
            throw new ApiException(503, "ingestion_unavailable",
                    "Ingestion Service answered " + response.status() + " for book " + bookId, RETRY_AFTER_SECONDS);
        }
        JsonElement status = response.json().get("status");
        if (status == null || !"available".equals(status.getAsString())) {
            throw new ApiException(409, "book_not_ready", "Book " + bookId + " has not completed ingestion");
        }
        return response.json();
    }

    private List<Integer> availableBooks() throws InterruptedException {
        ServiceClient.Response response = callIngestion("/ingest/list");
        if (response.status() != 200 || response.json() == null) {
            throw new ApiException(503, "ingestion_unavailable", "Ingestion Service could not list books", RETRY_AFTER_SECONDS);
        }
        List<Integer> ids = new ArrayList<>();
        response.json().getAsJsonArray("books").forEach(id -> ids.add(id.getAsInt()));
        return ids;
    }

    private ServiceClient.Response callIngestion(String path) throws InterruptedException {
        try {
            return ingestion.get(path);
        } catch (ServiceClient.UnavailableException e) {
            throw new ApiException(503, "ingestion_unavailable", e.getMessage(), RETRY_AFTER_SECONDS);
        }
    }

    private DatamartRepository.BookIndex parse(int bookId, JsonObject location) {
        String logicalPath = location.get("path").getAsString();
        Path base = datalake.resolve(logicalPath.substring("datalake/".length()));
        try {
            byte[] body = Files.readAllBytes(base.resolveSibling(base.getFileName() + ".body.txt"));
            String sha = sha256(body);
            if (!sha.equals(location.get("body_sha256").getAsString())) {
                throw new ApiException(500, "datalake_inconsistent", "Book " + bookId + " does not match its ingestion checksum");
            }
            String header = Files.readString(base.resolveSibling(base.getFileName() + ".header.txt"), StandardCharsets.UTF_8);
            return new DatamartRepository.BookIndex(bookId, extractor.extract(bookId, header),
                    Tokenizer.termFrequencies(new String(body, StandardCharsets.UTF_8)), sha);
        } catch (IOException e) {
            throw new ApiException(500, "datalake_inconsistent", "Book " + bookId + " is reported available but cannot be read: " + e.getMessage());
        }
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
