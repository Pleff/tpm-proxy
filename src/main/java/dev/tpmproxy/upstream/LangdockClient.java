package dev.tpmproxy.upstream;

import com.sun.net.httpserver.Headers;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;

/**
 * Forwards requests to Langdock's Anthropic-compatible endpoint
 * (SPEC.md Section 4). Langdock uses Bearer auth, not Anthropic's
 * native x-api-key header.
 */
public final class LangdockClient {

    private static final Set<String> SKIP_REQUEST_HEADERS = Set.of(
            "host", "content-length", "connection", "authorization",
            "x-api-key", "transfer-encoding", "upgrade", "expect");

    private final HttpClient httpClient;
    private final String baseUrl;
    private final String apiKey;

    public LangdockClient(String baseUrl, String apiKey) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /** Forwards the raw /v1/messages request body, passing through client headers except auth. */
    public <T> HttpResponse<T> forwardMessages(byte[] body, Headers clientHeaders, HttpResponse.BodyHandler<T> bodyHandler)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/messages"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        copyPassthroughHeaders(clientHeaders, builder);
        return httpClient.send(builder.build(), bodyHandler);
    }

    private static void copyPassthroughHeaders(Headers clientHeaders, HttpRequest.Builder builder) {
        clientHeaders.forEach((name, values) -> {
            if (SKIP_REQUEST_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                return;
            }
            for (String value : values) {
                try {
                    builder.header(name, value);
                } catch (IllegalArgumentException ignored) {
                    // Restricted header the JDK HttpClient won't let us set explicitly - skip it.
                }
            }
        });
    }
}
