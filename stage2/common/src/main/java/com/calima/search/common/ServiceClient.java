package com.calima.search.common;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * HTTP client for one logical service. Requests rotate over the replica endpoints, and a replica
 * that cannot be reached is skipped in favour of the next. An HTTP error response is returned to
 * the caller, because the replica did answer and only the caller knows whether to retry.
 */
public final class ServiceClient {

    /** Every configured replica failed at the connection level. */
    public static final class UnavailableException extends Exception {
        public UnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** An HTTP response, with the body parsed when it is a JSON object. */
    public record Response(int status, JsonObject json) {
        public String errorCode() {
            return json != null && json.has("error") ? json.get("error").getAsString() : null;
        }
    }

    private final List<String> endpoints;
    private final Duration timeout;
    private final HttpClient http;
    private final AtomicInteger next = new AtomicInteger();

    public ServiceClient(List<String> endpoints, Duration timeout) {
        if (endpoints.isEmpty()) {
            throw new IllegalArgumentException("At least one endpoint is required");
        }
        this.endpoints = List.copyOf(endpoints);
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2)).build();
    }

    public Response get(String path) throws UnavailableException, InterruptedException {
        return send("GET", path);
    }

    public Response post(String path) throws UnavailableException, InterruptedException {
        return send("POST", path);
    }

    private Response send(String method, String path) throws UnavailableException, InterruptedException {
        int first = Math.floorMod(next.getAndIncrement(), endpoints.size());
        IOException last = null;
        for (int offset = 0; offset < endpoints.size(); offset++) {
            String endpoint = endpoints.get((first + offset) % endpoints.size());
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint + path))
                    .timeout(timeout).method(method, HttpRequest.BodyPublishers.noBody()).build();
            try {
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                return new Response(response.statusCode(), parseObject(response.body()));
            } catch (IOException e) {
                last = e;
            }
        }
        throw new UnavailableException("No replica answered " + method + " " + path + " among " + endpoints, last);
    }

    private static JsonObject parseObject(String body) {
        try {
            var element = JsonParser.parseString(body);
            return element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
