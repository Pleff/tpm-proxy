package de.bestblu.tools.tpmproxy.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import de.bestblu.tools.tpmproxy.config.ProxyConfig;
import de.bestblu.tools.tpmproxy.limiter.DailyTokenLimiter;
import de.bestblu.tools.tpmproxy.limiter.SlidingWindowLimiter;
import de.bestblu.tools.tpmproxy.stats.ProxyStats;
import de.bestblu.tools.tpmproxy.upstream.LangdockClient;
import de.bestblu.tools.tpmproxy.upstream.TokenEstimator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests for {@link MessagesHandler}: a real {@link HttpServer}
 * running the handler, talking to a real (fake) upstream HTTP server
 * standing in for Langdock, exercised through a real {@link HttpClient}.
 */
class MessagesHandlerTest {

    private HttpServer upstream;
    private HttpServer proxy;
    private HttpClient httpClient;

    private final AtomicReference<HttpHandler> upstreamHandler = new AtomicReference<>();
    private final AtomicInteger upstreamRequestCount = new AtomicInteger();

    private SlidingWindowLimiter tpmLimiter;
    private DailyTokenLimiter dailyLimiter;
    private ProxyStats stats;

    @BeforeEach
    void setUp() throws IOException {
        upstream = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        upstream.createContext("/v1/messages", exchange -> {
            upstreamRequestCount.incrementAndGet();
            upstreamHandler.get().handle(exchange);
        });
        upstream.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        upstream.start();

        httpClient = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        upstream.stop(0);
        if (proxy != null) {
            proxy.stop(0);
        }
    }

    private String upstreamBaseUrl() {
        return "http://localhost:" + upstream.getAddress().getPort();
    }

    private void startProxy(ProxyConfig config, int tpmLimit, int dailyLimit) throws IOException {
        tpmLimiter = new SlidingWindowLimiter(tpmLimit);
        dailyLimiter = new DailyTokenLimiter(dailyLimit);
        stats = new ProxyStats();
        LangdockClient langdock = new LangdockClient(upstreamBaseUrl(), "upstream-key");
        TokenEstimator estimator = new TokenEstimator();
        ObjectMapper json = new ObjectMapper();

        proxy = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        proxy.createContext("/v1/messages",
                new MessagesHandler(config, tpmLimiter, dailyLimiter, langdock, estimator, json, stats));
        proxy.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        proxy.start();
    }

    private String proxyUrl() {
        return "http://localhost:" + proxy.getAddress().getPort() + "/v1/messages";
    }

    private ProxyConfig configWithToken(String token) {
        return configWithTokenAndTimeout(token, 2_000L);
    }

    private ProxyConfig configWithTokenAndTimeout(String token, long queueTimeoutMs) {
        return new ProxyConfig("upstream-key", upstreamBaseUrl(), 40_000, 1_000_000, 0, token, queueTimeoutMs);
    }

    // ---- tests ----

    @Test
    void rejectsNonPostRequestsWithMethodNotAllowed() throws Exception {
        startProxy(configWithToken(null), 1000, 1000);
        upstreamHandler.set(okJsonResponder(10, 5));

        HttpResponse<String> response = send("GET", null, null);

        assertEquals(405, response.statusCode());
        assertTrue(response.body().contains("invalid_request_error"));
    }

    @Test
    void rejectsRequestsWithoutTheConfiguredClientToken() throws Exception {
        startProxy(configWithToken("secret-token"), 1000, 1000);
        upstreamHandler.set(okJsonResponder(10, 5));

        HttpResponse<String> response = send("POST", validBody(), null);

        assertEquals(401, response.statusCode());
        assertEquals(0, upstreamRequestCount.get());
    }

    @Test
    void acceptsRequestsWithAValidXApiKeyHeader() throws Exception {
        startProxy(configWithToken("secret-token"), 1000, 1000);
        upstreamHandler.set(okJsonResponder(10, 5));

        HttpResponse<String> response = send("POST", validBody(), Map.of("x-api-key", "secret-token"));

        assertEquals(200, response.statusCode());
    }

    @Test
    void acceptsRequestsWithAValidBearerAuthorizationHeader() throws Exception {
        startProxy(configWithToken("secret-token"), 1000, 1000);
        upstreamHandler.set(okJsonResponder(10, 5));

        HttpResponse<String> response = send("POST", validBody(), Map.of("Authorization", "Bearer secret-token"));

        assertEquals(200, response.statusCode());
    }

    @Test
    void rejectsRequestsMissingMaxTokens() throws Exception {
        startProxy(configWithToken(null), 1000, 1000);
        upstreamHandler.set(okJsonResponder(10, 5));

        HttpResponse<String> response = send("POST", "{\"model\":\"claude\"}", null);

        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("max_tokens"));
        assertEquals(0, upstreamRequestCount.get());
    }

    @Test
    void forwardsAValidRequestAndCorrectsBothBudgetsToTheActualUsage() throws Exception {
        startProxy(configWithToken(null), 1000, 1000);
        upstreamHandler.set(okJsonResponder(7, 3));

        HttpResponse<String> response = send("POST", validBody(), null);

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"input_tokens\":7"));
        assertEquals(1, stats.totalRequests());
        assertEquals(10, stats.totalTokens());
        // the provisional (estimate + max_tokens) reservation must be corrected down to actual usage (7+3=10)
        assertEquals(10, tpmLimiter.snapshot().windowUsage());
        assertEquals(10, dailyLimiter.snapshot().usage());
    }

    @Test
    void returns429AndRetryAfterWhenTheTpmBudgetIsExhausted() throws Exception {
        startProxy(configWithTokenAndTimeout(null, 200L), 1, 1000);
        upstreamHandler.set(okJsonResponder(7, 3));

        HttpResponse<String> response = send("POST", validBody(), null);

        assertEquals(429, response.statusCode());
        assertTrue(response.headers().firstValue("retry-after").isPresent());
        assertTrue(response.body().contains("TPM"));
        assertEquals(0, upstreamRequestCount.get(), "upstream must never be called once the TPM budget check fails");
    }

    @Test
    void returns429WhenTheDailyBudgetIsExhaustedAndReleasesTheTpmReservationAgain() throws Exception {
        startProxy(configWithToken(null), 100_000, 1);
        upstreamHandler.set(okJsonResponder(7, 3));

        HttpResponse<String> response = send("POST", validBody(), null);

        assertEquals(429, response.statusCode());
        assertTrue(response.body().contains("daily"));
        assertEquals(0, upstreamRequestCount.get());
        assertEquals(0, tpmLimiter.snapshot().windowUsage(),
                "the TPM reservation made before the daily check failed must be released again");
    }

    @Test
    void passesThroughUpstreamErrorStatusesAndReleasesTheReservation() throws Exception {
        startProxy(configWithToken(null), 1000, 1000);
        upstreamHandler.set(exchange -> {
            byte[] body = "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"boom\"}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(529, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });

        HttpResponse<String> response = send("POST", validBody(), null);

        assertEquals(529, response.statusCode());
        assertTrue(response.body().contains("overloaded_error"));
        assertEquals(0, tpmLimiter.snapshot().windowUsage(), "no tokens should be charged for a failed upstream call");
        assertEquals(0, stats.totalRequests(), "a failed call is not counted as a completed request");
    }

    @Test
    void parsesUsageFromAStreamingSseResponseAndForwardsTheBodyUnchanged() throws Exception {
        startProxy(configWithToken(null), 1000, 1000);
        upstreamHandler.set(exchange -> {
            String sse = "event: message_start\n"
                    + "data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":8}}}\n\n"
                    + "event: message_delta\n"
                    + "data: {\"type\":\"message_delta\",\"usage\":{\"output_tokens\":4}}\n\n";
            byte[] bytes = sse.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });

        String body = "{\"model\":\"claude\",\"max_tokens\":50,\"stream\":true,\"messages\":"
                + "[{\"role\":\"user\",\"content\":\"hi\"}]}";
        HttpResponse<String> response = send("POST", body, null);

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("message_start"));
        assertTrue(response.body().contains("message_delta"));
        assertEquals(12, tpmLimiter.snapshot().windowUsage(), "8 input + 4 output tokens reported via the SSE events");
    }

    // ---- helpers ----

    private HttpHandler okJsonResponder(int inputTokens, int outputTokens) {
        return exchange -> {
            String json = "{\"id\":\"msg_1\",\"usage\":{\"input_tokens\":%d,\"output_tokens\":%d}}"
                    .formatted(inputTokens, outputTokens);
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        };
    }

    private String validBody() {
        return "{\"model\":\"claude-3-5-sonnet\",\"max_tokens\":50,\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}";
    }

    private HttpResponse<String> send(String method, String body, Map<String, String> headers)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(proxyUrl()));
        if (body != null) {
            builder.method(method, HttpRequest.BodyPublishers.ofString(body));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        if (headers != null) {
            headers.forEach(builder::header);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
