package com.calima.search.ingestion;

import com.calima.search.common.ApiException;
import com.calima.search.common.Faults;
import java.io.IOException;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Ingests one book: claim it, download, split, write the files, and only then mark it available. */
public final class IngestionService {

    private static final int RETRY_AFTER_SECONDS = 2;

    public record Outcome(int bookId, String status, String path) {
    }

    private final IngestionLedger ledger;
    private final BookStore store;
    private final GutenbergClient gutenberg;

    public IngestionService(IngestionLedger ledger, BookStore store, GutenbergClient gutenberg) {
        this.ledger = ledger;
        this.store = store;
        this.gutenberg = gutenberg;
    }

    public Outcome ingest(int bookId) throws SQLException, InterruptedException {
        String requestId = UUID.randomUUID().toString();
        IngestionLedger.Claim claim = claim(bookId, requestId);
        if (claim.result() == IngestionLedger.ClaimResult.ALREADY_AVAILABLE) {
            return new Outcome(bookId, "already_available", claim.entry().path());
        }
        if (claim.result() == IngestionLedger.ClaimResult.HELD_BY_ANOTHER) {
            throw inProgress(bookId);
        }
        Faults.point("ingest_after_claim");

        String path = claim.entry().path();
        try {
            GutenbergParser.Book book = GutenbergParser.split(gutenberg.download(bookId));
            BookStore.Stored stored = store.write(path, requestId, book);
            Faults.point("ingest_before_commit");
            if (!ledger.complete(bookId, requestId, stored, System.currentTimeMillis())) {
                // The lease expired and another request took over; that request reports the outcome.
                throw inProgress(bookId);
            }
            Faults.point("ingest_after_commit");
            return new Outcome(bookId, "downloaded", path);
        } catch (GutenbergClient.BookNotFoundException e) {
            release(bookId, requestId, "book_not_found");
            throw new ApiException(404, "book_not_found", "Project Gutenberg has no book " + bookId);
        } catch (GutenbergParser.InvalidBookException e) {
            release(bookId, requestId, e.getMessage());
            throw new ApiException(422, "invalid_book_format", "Book " + bookId + ": " + e.getMessage());
        } catch (IOException e) {
            release(bookId, requestId, e.toString());
            throw new ApiException(502, "upstream_unavailable", "Could not fetch or store book " + bookId + ": " + e.getMessage(),
                    RETRY_AFTER_SECONDS);
        }
    }

    public IngestionLedger.Entry status(int bookId) throws SQLException {
        return ledger.get(bookId);
    }

    public List<Integer> listAvailable() throws SQLException {
        return ledger.listAvailable();
    }

    private IngestionLedger.Claim claim(int bookId, String requestId) throws SQLException {
        Instant now = Instant.now();
        return ledger.claim(bookId, requestId, BookStore.logicalPath(bookId, now), now.toEpochMilli());
    }

    private void release(int bookId, String requestId, String error) throws SQLException {
        ledger.fail(bookId, requestId, error, System.currentTimeMillis());
    }

    private static ApiException inProgress(int bookId) {
        return new ApiException(409, "ingestion_in_progress", "Book " + bookId + " is being ingested by another request",
                RETRY_AFTER_SECONDS);
    }
}
