package de.bestblu.tools.tpmproxy.upstream;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link LangdockClient} against a real local HTTP server standing
 * in for Langdock, so we can inspect exactly what gets forwarded - in
 * particular a regression guard for the duplicate Content-Type header bug
 * fixed in commit 6f826c4.
 */
class LangdockClientTest {

    private HttpServer upstream;
    private volatile CapturedRequest captured;

    @BeforeEach
    void startUpstream() throws IOException {
        upstream = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        upstream.createContext("/v1/messages", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            captured = new CapturedRequest(exchange.getRequestHeaders(), body);

            byte[] response = "{\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var out = exchange.getResponseBody()) {
                out.write(response);
            }
        });
        upstream.setExecutor(Executors.newSingleThreadExecutor());
        upstream.start();
    }

    @AfterEach
    void stopUpstream() {
        upstream.stop(0);
    }

    private String baseUrl() {
        return "http://localhost:" + upstream.getAddress().getPort();
    }

    @Test
    void sendsBearerAuthorizationWithTheServerSideKeyNotTheClientsCredentials() throws Exception {
        LangdockClient client = new LangdockClient(baseUrl(), "server-side-key");
        Headers clientHeaders = new Headers();
        clientHeaders.add("Authorization", "Bearer client-side-token");
        clientHeaders.add("x-api-key", "client-api-key");

        client.forwardMessages("{}".getBytes(StandardCharsets.UTF_8), clientHeaders, HttpResponse.BodyHandlers.ofByteArray());

        assertNotNull(captured);
        assertEquals(List.of("Bearer server-side-key"), captured.headers.get("Authorization"));
    }

    @Test
    void setsContentTypeExactlyOnceEvenWhenTheClientAlsoSentOne() throws Exception {
        // Regression test for the bug fixed in 6f826c4: forwarding the client's own
        // Content-Type header in addition to the one this client sets broke Langdock's parser.
        LangdockClient client = new LangdockClient(baseUrl(), "key");
        Headers clientHeaders = new Headers();
        clientHeaders.add("Content-Type", "text/plain; charset=us-ascii");

        client.forwardMessages("{}".getBytes(StandardCharsets.UTF_8), clientHeaders, HttpResponse.BodyHandlers.ofByteArray());

        assertNotNull(captured);
        List<String> contentTypeValues = captured.headers.get("Content-Type");
        assertEquals(1, contentTypeValues.size(), "Content-Type must be set exactly once, got: " + contentTypeValues);
        assertEquals("application/json", contentTypeValues.get(0));
    }

    @Test
    void forwardsTheRequestBodyUnchanged() throws Exception {
        LangdockClient client = new LangdockClient(baseUrl(), "key");
        byte[] body = "{\"model\":\"claude\",\"max_tokens\":10}".getBytes(StandardCharsets.UTF_8);

        client.forwardMessages(body, new Headers(), HttpResponse.BodyHandlers.ofByteArray());

        assertArrayEquals(body, captured.body);
    }

    @Test
    void passesThroughCustomClientHeaders() throws Exception {
        LangdockClient client = new LangdockClient(baseUrl(), "key");
        Headers clientHeaders = new Headers();
        clientHeaders.add("X-Custom-Header", "custom-value");

        client.forwardMessages("{}".getBytes(StandardCharsets.UTF_8), clientHeaders, HttpResponse.BodyHandlers.ofByteArray());

        assertEquals(List.of("custom-value"), captured.headers.get("X-Custom-Header"));
    }

    @Test
    void skipsHopByHopAndAuthRelatedClientHeadersInsteadOfLeakingThem() throws Exception {
        LangdockClient client = new LangdockClient(baseUrl(), "key");
        Headers clientHeaders = new Headers();
        clientHeaders.add("Host", "opencode.local");
        clientHeaders.add("Connection", "keep-alive");
        clientHeaders.add("Content-Length", "2");

        client.forwardMessages("{}".getBytes(StandardCharsets.UTF_8), clientHeaders, HttpResponse.BodyHandlers.ofByteArray());

        assertNotNull(captured);
        assertNotEquals("opencode.local", captured.headers.getFirst("Host"),
                "the client's Host header must not override the real upstream target");
        String connection = captured.headers.getFirst("Connection");
        if (connection != null) {
            assertNotEquals("keep-alive", connection.toLowerCase(java.util.Locale.ROOT));
        }
    }

    @Test
    void toleratesABaseUrlWithATrailingSlash() throws Exception {
        LangdockClient client = new LangdockClient(baseUrl() + "/", "key");

        HttpResponse<byte[]> response = client.forwardMessages(
                "{}".getBytes(StandardCharsets.UTF_8), new Headers(), HttpResponse.BodyHandlers.ofByteArray());

        assertEquals(200, response.statusCode());
        assertNotNull(captured, "the request must have reached /v1/messages, not //v1/messages");
    }

    @Test
    void returnsTheUpstreamResponseBodyAndStatusUnchanged() throws Exception {
        LangdockClient client = new LangdockClient(baseUrl(), "key");

        HttpResponse<byte[]> response = client.forwardMessages(
                "{}".getBytes(StandardCharsets.UTF_8), new Headers(), HttpResponse.BodyHandlers.ofByteArray());

        assertEquals(200, response.statusCode());
        assertTrue(new String(response.body(), StandardCharsets.UTF_8).contains("input_tokens"));
    }

    private record CapturedRequest(Headers headers, byte[] body) {
    }
}
