package com.g8scope;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Default transport: POST the envelope to {endpoint}/v1/ingest using the JDK's
 * built-in {@link HttpClient} (no third-party HTTP library). Swallows all errors —
 * capturing must never break the monitored app.
 */
public final class HttpTransport implements Transport {

    private final HttpClient client;

    public HttpTransport() {
        this.client = HttpClient.newHttpClient();
    }

    public HttpTransport(HttpClient client) {
        this.client = client;
    }

    @Override
    public void send(String endpoint, String key, Map<String, Object> envelope, long timeoutMillis) {
        try {
            String base = endpoint == null ? "" : endpoint.replaceAll("/+$", "");
            String url = base + "/v1/ingest";
            String body = Json.encode(envelope);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(Math.max(1, timeoutMillis)))
                    .header("Content-Type", "application/json")
                    .header("X-Ingest-Key", key)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            client.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (Throwable ignored) {
            // Capturing must never break the monitored app.
        }
    }
}
