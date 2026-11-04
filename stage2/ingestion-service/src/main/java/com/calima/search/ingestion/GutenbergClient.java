package com.calima.search.ingestion;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Downloads raw book text from a configurable URL template, which tests point at a local server. */
public final class GutenbergClient {

    /** The source reported that this book does not exist; retrying would not help. */
    public static final class BookNotFoundException extends Exception {
    }

    private final String urlTemplate;
    private final Duration timeout;
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public GutenbergClient(String urlTemplate, Duration timeout) {
        this.urlTemplate = urlTemplate;
        this.timeout = timeout;
    }

    /** @throws IOException on a network failure or an unexpected status, both worth retrying */
    public String download(int bookId) throws IOException, InterruptedException, BookNotFoundException {
        URI uri = URI.create(urlTemplate.replace("{id}", Integer.toString(bookId)));
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(timeout)
                .header("User-Agent", "calima-search-engine/1.0").GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() == 404) {
            throw new BookNotFoundException();
        }
        if (response.statusCode() != 200) {
            throw new IOException("Unexpected status " + response.statusCode() + " from " + uri);
        }
        return response.body();
    }
}
