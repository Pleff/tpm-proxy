package dev.tpmproxy.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import dev.tpmproxy.config.ProxyConfig;
import dev.tpmproxy.limiter.SlidingWindowLimiter;
import dev.tpmproxy.limiter.SlidingWindowLimiter.Reservation;
import dev.tpmproxy.upstream.LangdockClient;
import dev.tpmproxy.upstream.TokenEstimator;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * POST /v1/messages - the proxy's core path (SPEC.md Sections 5-6):
 * preflight-reserve a TPM budget, forward to Langdock (streaming or not),
 * then correct the reservation to the actual usage.
 */
public class MessagesHandler implements HttpHandler {

    private static final Set<String> SKIP_RESPONSE_HEADERS = Set.of("content-length", "transfer-encoding", "connection");

    private final ProxyConfig config;
    private final SlidingWindowLimiter limiter;
    private final LangdockClient langdock;
    private final TokenEstimator estimator;
    private final ObjectMapper json;

    public MessagesHandler(ProxyConfig config, SlidingWindowLimiter limiter, LangdockClient langdock,
                            TokenEstimator estimator, ObjectMapper json) {
        this.config = config;
        this.limiter = limiter;
        this.langdock = langdock;
        this.estimator = estimator;
        this.json = json;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            JsonHttp.writeAnthropicError(exchange, json, 405, "invalid_request_error", "method not allowed");
            return;
        }

        if (!isAuthorized(exchange)) {
            JsonHttp.writeAnthropicError(exchange, json, 401, "authentication_error", "missing or invalid proxy client token");
            return;
        }

        byte[] rawBody = exchange.getRequestBody().readAllBytes();
        JsonNode requestJson;
        try {
            requestJson = json.readTree(rawBody);
        } catch (IOException e) {
            JsonHttp.writeAnthropicError(exchange, json, 400, "invalid_request_error", "malformed JSON body");
            return;
        }
        if (requestJson == null || !requestJson.isObject()) {
            JsonHttp.writeAnthropicError(exchange, json, 400, "invalid_request_error", "request body must be a JSON object");
            return;
        }

        int maxTokens = requestJson.path("max_tokens").asInt(0);
        if (maxTokens <= 0) {
            JsonHttp.writeAnthropicError(exchange, json, 400, "invalid_request_error", "max_tokens is required");
            return;
        }
        boolean streaming = requestJson.path("stream").asBoolean(false);

        int estimatedInputTokens = estimator.estimateInputTokens(requestJson);
        int reservationTokens = estimatedInputTokens + maxTokens;

        Reservation reservation;
        try {
            Optional<Reservation> reserved = limiter.reserveBlocking(reservationTokens, config.queueTimeoutMs());
            if (reserved.isEmpty()) {
                sendRateLimitError(exchange, limiter.millisUntilAvailable(reservationTokens));
                return;
            }
            reservation = reserved.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            JsonHttp.writeAnthropicError(exchange, json, 500, "api_error", "interrupted while waiting for TPM budget");
            return;
        }

        try {
            if (streaming) {
                forwardStreaming(exchange, rawBody, reservation);
            } else {
                forwardNonStreaming(exchange, rawBody, reservation);
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            limiter.release(reservation);
            JsonHttp.writeAnthropicError(exchange, json, 502, "api_error", "upstream request to Langdock failed: " + e.getMessage());
        }
    }

    private void forwardNonStreaming(HttpExchange exchange, byte[] rawBody, Reservation reservation)
            throws IOException, InterruptedException {
        HttpResponse<byte[]> response = langdock.forwardMessages(rawBody, exchange.getRequestHeaders(),
                HttpResponse.BodyHandlers.ofByteArray());
        byte[] responseBody = response.body();

        if (response.statusCode() == 200) {
            JsonNode responseJson = json.readTree(responseBody);
            JsonNode usage = responseJson.get("usage");
            if (usage != null) {
                int actual = usage.path("input_tokens").asInt(0) + usage.path("output_tokens").asInt(0);
                limiter.correct(reservation, actual);
            } else {
                limiter.release(reservation);
            }
        } else {
            // Real error from Langdock (SPEC.md Section 5.3) - passed through unchanged; no tokens were spent.
            limiter.release(reservation);
        }

        copyResponseHeaders(response, exchange);
        exchange.sendResponseHeaders(response.statusCode(), responseBody.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(responseBody);
        }
    }

    private void forwardStreaming(HttpExchange exchange, byte[] rawBody, Reservation reservation)
            throws IOException, InterruptedException {
        HttpResponse<InputStream> response = langdock.forwardMessages(rawBody, exchange.getRequestHeaders(),
                HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() != 200) {
            limiter.release(reservation);
            byte[] errorBody = response.body().readAllBytes();
            copyResponseHeaders(response, exchange);
            exchange.sendResponseHeaders(response.statusCode(), errorBody.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(errorBody);
            }
            return;
        }

        copyResponseHeaders(response, exchange);
        exchange.sendResponseHeaders(200, 0); // chunked - length unknown up front

        int[] usage = {0, 0}; // {input_tokens, output_tokens}, updated as SSE events arrive (SPEC.md Section 5.1)
        try (InputStream in = response.body();
             OutputStream out = exchange.getResponseBody();
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                out.write(line.getBytes(StandardCharsets.UTF_8));
                out.write('\n');
                out.flush();

                if (line.startsWith("data:")) {
                    captureUsage(line.substring(5).trim(), usage);
                }
            }
        } finally {
            int actual = usage[0] + usage[1];
            if (actual > 0) {
                limiter.correct(reservation, actual);
            } else {
                // Stream ended without ever reporting usage (e.g. aborted mid-flight) - keep the
                // reservation as-is rather than releasing it (SPEC.md Section 5.1: no negative rebooking).
                limiter.correct(reservation, reservation.tokens());
            }
        }
    }

    private void captureUsage(String eventData, int[] usage) {
        try {
            JsonNode event = json.readTree(eventData);
            String type = event.path("type").asText("");
            if ("message_start".equals(type)) {
                usage[0] = event.path("message").path("usage").path("input_tokens").asInt(usage[0]);
            } else if ("message_delta".equals(type)) {
                usage[1] = event.path("usage").path("output_tokens").asInt(usage[1]);
            }
        } catch (IOException ignored) {
            // Not a JSON data line (e.g. SSE comment/keepalive) - the raw bytes were already forwarded.
        }
    }

    private void copyResponseHeaders(HttpResponse<?> response, HttpExchange exchange) {
        response.headers().map().forEach((name, values) -> {
            if (SKIP_RESPONSE_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                return;
            }
            for (String value : values) {
                exchange.getResponseHeaders().add(name, value);
            }
        });
    }

    private void sendRateLimitError(HttpExchange exchange, long retryAfterMillis) throws IOException {
        long retryAfterSeconds = Math.max(1, (retryAfterMillis + 999) / 1000);
        exchange.getResponseHeaders().set("retry-after", String.valueOf(retryAfterSeconds));
        JsonHttp.writeAnthropicError(exchange, json, 429, "rate_limit_error",
                "tpm-proxy: local TPM budget exhausted, retry later");
    }

    /**
     * Accepts either header convention client tools use for a configured API key:
     * plain {@code x-api-key} (e.g. opencode's built-in Anthropic provider) or
     * {@code Authorization: Bearer <token>} (e.g. ANTHROPIC_AUTH_TOKEN in Claude Code).
     */
    private boolean isAuthorized(HttpExchange exchange) {
        String expectedToken = config.proxyClientToken();
        if (expectedToken == null) {
            return true;
        }
        byte[] expected = expectedToken.getBytes(StandardCharsets.UTF_8);

        String apiKeyHeader = exchange.getRequestHeaders().getFirst("x-api-key");
        if (apiKeyHeader != null && MessageDigest.isEqual(apiKeyHeader.getBytes(StandardCharsets.UTF_8), expected)) {
            return true;
        }

        List<String> authHeaders = exchange.getRequestHeaders().get("Authorization");
        if (authHeaders != null && !authHeaders.isEmpty()) {
            byte[] expectedBearer = ("Bearer " + expectedToken).getBytes(StandardCharsets.UTF_8);
            return MessageDigest.isEqual(authHeaders.get(0).getBytes(StandardCharsets.UTF_8), expectedBearer);
        }

        return false;
    }
}
