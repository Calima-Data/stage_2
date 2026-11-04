package com.calima.search.tools;

import com.calima.search.common.Config;
import io.javalin.Javalin;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Local stand-in for Project Gutenberg, so that benchmarks measure this system rather than the
 * Internet and do not load a public service. It serves {@code GET /{id}} from a corpus folder.
 *
 * <p>Environment: {@code PORT}, {@code CORPUS_DIR}, {@code MAX_BOOK_ID} (higher IDs get 404) and
 * {@code LATENCY_MS} (simulated network delay). IDs that have no file of their own replay a corpus
 * file chosen by {@code id % corpus size}: the workload can grow beyond the number of real books,
 * with the limitation that replayed books do not add new vocabulary.
 */
public final class MockGutenberg {

    private MockGutenberg() {
    }

    public static void main(String[] args) throws IOException {
        Config config = Config.load();
        Path corpusDir = Path.of(config.get("CORPUS_DIR", "fixtures/corpus"));
        int maxBookId = config.getInt("MAX_BOOK_ID", Integer.MAX_VALUE);
        int latencyMs = config.getInt("LATENCY_MS", 0);

        List<Path> corpus;
        try (Stream<Path> files = Files.list(corpusDir)) {
            corpus = files.filter(p -> p.toString().endsWith(".txt")).sorted().toList();
        }
        if (corpus.isEmpty()) {
            throw new IllegalArgumentException("No .txt files in " + corpusDir);
        }

        Javalin app = Javalin.create(c -> c.showJavalinBanner = false);
        app.get("/{id}", ctx -> {
            int id;
            try {
                id = Integer.parseInt(ctx.pathParam("id"));
            } catch (NumberFormatException e) {
                ctx.status(404);
                return;
            }
            if (id > maxBookId) {
                ctx.status(404);
                return;
            }
            if (latencyMs > 0) {
                Thread.sleep(latencyMs);
            }
            Path own = corpusDir.resolve(id + ".txt");
            Path file = Files.exists(own) ? own : corpus.get(id % corpus.size());
            ctx.contentType("text/plain; charset=utf-8").result(Files.readAllBytes(file));
        });
        app.start(config.getInt("PORT", 7900));
    }
}
