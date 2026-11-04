package com.calima.search.orchestrator;

import com.calima.search.common.Faults;
import com.calima.search.common.ServiceClient;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Worker threads that drive each book one step at a time: claim a ready task, call the service
 * responsible for its current state, and record the outcome.
 *
 * <p>Different books proceed in parallel, while the claim guarantees that one book has one worker.
 * A worker holds no lock during the HTTP call, so a slow service only occupies that worker.
 */
public final class Orchestrator {

    public record Settings(int workers, long pollMs, int maxAttempts, long retryBaseMs, long retryMaxMs) {
    }

    private enum Verdict { DONE, RETRY, REWIND, FAIL }

    private record StepResult(Verdict verdict, String detail) {
    }

    private static final Logger LOG = LoggerFactory.getLogger(Orchestrator.class);

    private final TaskStore store;
    private final ServiceClient ingestion;
    private final ServiceClient indexing;
    private final ServiceClient search;
    private final Settings settings;
    private final List<Thread> threads = new ArrayList<>();
    private volatile boolean running;

    public Orchestrator(TaskStore store, ServiceClient ingestion, ServiceClient indexing, ServiceClient search,
                        Settings settings) {
        this.store = store;
        this.ingestion = ingestion;
        this.indexing = indexing;
        this.search = search;
        this.settings = settings;
    }

    public void start() {
        running = true;
        for (int i = 0; i < settings.workers(); i++) {
            String owner = "worker-" + i;
            Thread thread = new Thread(() -> workLoop(owner), "orchestrator-" + owner);
            thread.setDaemon(true);
            thread.start();
            threads.add(thread);
        }
    }

    public void stop() throws InterruptedException {
        running = false;
        for (Thread thread : threads) {
            thread.interrupt();
        }
        for (Thread thread : threads) {
            thread.join(5_000);
        }
    }

    private void workLoop(String owner) {
        while (running) {
            try {
                long claimStart = System.nanoTime();
                TaskStore.Task task = store.claimNext(owner, System.currentTimeMillis());
                long claimNanos = System.nanoTime() - claimStart;
                if (task == null) {
                    Thread.sleep(settings.pollMs());
                } else {
                    runStep(task, owner, claimNanos);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (SQLException | RuntimeException e) {
                // A busy database or an unexpected error must not kill the worker. The claim, if
                // any, expires by its lease and the task is picked up again.
                LOG.warn("Worker {} could not complete a step: {}", owner, e.toString());
                try {
                    Thread.sleep(settings.pollMs());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void runStep(TaskStore.Task task, String owner, long claimNanos) throws SQLException, InterruptedException {
        int bookId = task.bookId();
        long start = System.currentTimeMillis();
        StepResult result;
        String nextState;
        String rewindState;
        String timingColumn;
        switch (task.state()) {
            case TaskStore.PENDING -> {
                result = ingest(bookId);
                nextState = TaskStore.INGESTED;
                rewindState = TaskStore.PENDING;
                timingColumn = "ingest_ms";
            }
            case TaskStore.INGESTED -> {
                result = index(bookId);
                nextState = TaskStore.INDEXED;
                rewindState = TaskStore.PENDING;
                timingColumn = "index_ms";
            }
            case TaskStore.INDEXED -> {
                result = verifyPublished(bookId);
                nextState = TaskStore.PUBLISHED;
                rewindState = TaskStore.INGESTED;
                timingColumn = "publish_ms";
            }
            default -> throw new IllegalStateException("Claimed a task in state " + task.state());
        }
        long now = System.currentTimeMillis();
        Faults.point("control_before_record_" + task.state().toLowerCase());
        switch (result.verdict()) {
            case DONE -> store.advance(bookId, owner, nextState,
                    new TaskStore.StepTiming(timingColumn, now - start, claimNanos), now);
            case RETRY -> scheduleRetry(task, owner, task.state(), result.detail(), now);
            case REWIND -> scheduleRetry(task, owner, rewindState, result.detail(), now);
            case FAIL -> {
                LOG.warn("Book {} failed permanently: {}", bookId, result.detail());
                store.fail(bookId, owner, result.detail());
            }
        }
    }

    private void scheduleRetry(TaskStore.Task task, String owner, String state, String detail, long now)
            throws SQLException {
        if (task.attempts() + 1 >= settings.maxAttempts()) {
            LOG.warn("Book {} exhausted its {} attempts: {}", task.bookId(), settings.maxAttempts(), detail);
            store.fail(task.bookId(), owner, "attempts exhausted: " + detail);
            return;
        }
        store.retry(task.bookId(), owner, state, detail, now + backoffMillis(task.attempts()));
    }

    /** Exponential backoff with jitter, so that retries from many workers do not arrive together. */
    private long backoffMillis(int attempts) {
        long delay = Math.min(settings.retryMaxMs(), settings.retryBaseMs() << Math.min(attempts, 20));
        return delay / 2 + ThreadLocalRandom.current().nextLong(delay / 2 + 1);
    }

    private StepResult ingest(int bookId) throws InterruptedException {
        return call(ingestion, true, "/ingest/" + bookId, response -> switch (response.status()) {
            case 200 -> new StepResult(Verdict.DONE, null);
            case 404, 422, 400 -> new StepResult(Verdict.FAIL, describe("ingest", response));
            default -> new StepResult(Verdict.RETRY, describe("ingest", response));
        });
    }

    private StepResult index(int bookId) throws InterruptedException {
        return call(indexing, true, "/index/update/" + bookId, response -> {
            if (response.status() == 200) {
                return new StepResult(Verdict.DONE, null);
            }
            if ("book_not_ready".equals(response.errorCode())) {
                // Ingestion no longer reports the book as ready, so that step must run again.
                return new StepResult(Verdict.REWIND, describe("index", response));
            }
            return new StepResult(response.status() == 400 ? Verdict.FAIL : Verdict.RETRY, describe("index", response));
        });
    }

    /** The end of the workflow: the book must be visible through the API that front-ends use. */
    private StepResult verifyPublished(int bookId) throws InterruptedException {
        return call(search, false, "/books/" + bookId, response -> response.status() == 200
                ? new StepResult(Verdict.DONE, null)
                : new StepResult(Verdict.RETRY, describe("publish", response)));
    }

    @FunctionalInterface
    private interface Classifier {
        StepResult classify(ServiceClient.Response response);
    }

    private static StepResult call(ServiceClient client, boolean post, String path, Classifier classifier)
            throws InterruptedException {
        try {
            return classifier.classify(post ? client.post(path) : client.get(path));
        } catch (ServiceClient.UnavailableException e) {
            return new StepResult(Verdict.RETRY, e.getMessage());
        }
    }

    private static String describe(String step, ServiceClient.Response response) {
        return step + " answered " + response.status() + (response.errorCode() == null ? "" : " " + response.errorCode());
    }
}
