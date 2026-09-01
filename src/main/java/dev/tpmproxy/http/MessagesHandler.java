package dev.tpmproxy.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import dev.tpmproxy.config.ProxyConfig;
import dev.tpmproxy.limiter.DailyTokenLimiter;
import dev.tpmproxy.limiter.SlidingWindowLimiter;
import dev.tpmproxy.limiter.SlidingWindowLimiter.Reservation;
import dev.tpmproxy.stats.ProxyStats;
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
 * preflight-reserve both the TPM and the daily token budget, forward to
 * Langdock (streaming or not), then correct both reservations to the
 * actual usage.
 */
public class MessagesHandler implements HttpHandler {

    private static final Set<String> SKIP_RESPONSE_HEADERS = Set.of("content-length", "transfer-encoding", "connection");

    private final ProxyConfig config;
    private final SlidingWindowLimiter tpmLimiter;
    private final DailyTokenLimiter dailyLimiter;
    private final LangdockClient langdock;
    private final TokenEstimator estimator;
    private final ObjectMapper json;
    private final ProxyStats stats;

    public MessagesHandler(ProxyConfig config, SlidingWindowLimiter tpmLimiter, DailyTokenLimiter dailyLimiter,
                            LangdockClient langdock, TokenEstimator estimator, ObjectMapper json, ProxyStats stats) {
        this.config = config;
        this.tpmLimiter = tpmLimiter;
        this.dailyLimiter = dailyLimiter;
        this.langdock = langdock;
        this.estimator = estimator;
        this.json = json;
        this.stats = stats;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        long startNanos = System.nanoTime();

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

        String model = requestJson.path("model").asText("unknown");
        int maxTokens = requestJson.path("max_tokens").asInt(0);
        if (maxTokens <= 0) {
            JsonHttp.writeAnthropicError(exchange, json, 400, "invalid_request_error", "max_tokens is required");
            return;
        }
        boolean streaming = requestJson.path("stream").asBoolean(false);

        int estimatedInputTokens = estimator.estimateInputTokens(requestJson);
        int reservationTokens = estimatedInputTokens + maxTokens;

        Reservation tpmReservation;
        try {
            Optional<Reservation> reserved = tpmLimiter.reserveBlocking(reservationTokens, config.queueTimeoutMs());
            if (reserved.isEmpty()) {
                long retryAfterMillis = tpmLimiter.millisUntilAvailable(reservationTokens);
                sendRateLimitError(exchange, "TPM", retryAfterMillis);
                logRejected(model, streaming, "TPM", retryAfterMillis, startNanos);
                return;
            }
            tpmReservation = reserved.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            JsonHttp.writeAnthropicError(exchange, json, 500, "api_error", "interrupted while waiting for TPM budget");
            return;
        }

        // Daily budget: a calendar-day counter, not a rolling window - no point blocking on
        // it, waiting a few seconds won't bring midnight any closer, so fail fast instead.
        Optional<DailyTokenLimiter.Reservation> dailyReserved = dailyLimiter.tryReserve(reservationTokens);
        if (dailyReserved.isEmpty()) {
            tpmLimiter.release(tpmReservation);
            long retryAfterMillis = dailyLimiter.millisUntilAvailable(reservationTokens);
            sendRateLimitError(exchange, "daily token", retryAfterMillis);
            logRejected(model, streaming, "daily token", retryAfterMillis, startNanos);
            return;
        }
        Budget budget = new Budget(tpmReservation, dailyReserved.get());

        try {
            if (streaming) {
                forwardStreaming(exchange, rawBody, budget, model, startNanos);
            } else {
                forwardNonStreaming(exchange, rawBody, budget, model, startNanos);
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            releaseBudget(budget);
            JsonHttp.writeAnthropicError(exchange, json, 502, "api_error", "upstream request to Langdock failed: " + e.getMessage());
        }
    }

    private void forwardNonStreaming(HttpExchange exchange, byte[] rawBody, Budget budget, String model, long startNanos)
            throws IOException, InterruptedException {
        HttpResponse<byte[]> response = langdock.forwardMessages(rawBody, exchange.getRequestHeaders(),
                HttpResponse.BodyHandlers.ofByteArray());
        byte[] responseBody = response.body();

        if (response.statusCode() == 200) {
            JsonNode responseJson = json.readTree(responseBody);
            JsonNode usage = responseJson.get("usage");
            int inputTokens = 0;
            int outputTokens = 0;
            if (usage != null) {
                inputTokens = usage.path("input_tokens").asInt(0);
                outputTokens = usage.path("output_tokens").asInt(0);
                correctBudget(budget, inputTokens + outputTokens);
            } else {
                releaseBudget(budget);
            }
            logCompleted(model, false, inputTokens, outputTokens, startNanos);
        } else {
            // Real error from Langdock (SPEC.md Section 5.3) - passed through unchanged; no tokens were spent.
            releaseBudget(budget);
            logFailed(model, false, response.statusCode(), startNanos);
        }

        copyResponseHeaders(response, exchange);
        exchange.sendResponseHeaders(response.statusCode(), responseBody.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(responseBody);
        }
    }

    private void forwardStreaming(HttpExchange exchange, byte[] rawBody, Budget budget, String model, long startNanos)
            throws IOException, InterruptedException {
        HttpResponse<InputStream> response = langdock.forwardMessages(rawBody, exchange.getRequestHeaders(),
                HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() != 200) {
            releaseBudget(budget);
            logFailed(model, true, response.statusCode(), startNanos);
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
                correctBudget(budget, actual);
            } else {
                // Stream ended without ever reporting usage (e.g. aborted mid-flight) - keep the
                // reservation as-is rather than releasing it (SPEC.md Section 5.1: no negative rebooking).
                correctBudget(budget, budget.tpm().tokens());
            }
            logCompleted(model, true, usage[0], usage[1], startNanos);
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

    private void correctBudget(Budget budget, int actualTokens) {
        tpmLimiter.correct(budget.tpm(), actualTokens);
        dailyLimiter.correct(budget.daily(), actualTokens);
    }

    private void releaseBudget(Budget budget) {
        tpmLimiter.release(budget.tpm());
        dailyLimiter.release(budget.daily());
    }

    private void sendRateLimitError(HttpExchange exchange, String scope, long retryAfterMillis) throws IOException {
        long retryAfterSeconds = Math.max(1, (retryAfterMillis + 999) / 1000);
        exchange.getResponseHeaders().set("retry-after", String.valueOf(retryAfterSeconds));
        JsonHttp.writeAnthropicError(exchange, json, 429, "rate_limit_error",
                "tpm-proxy: local " + scope + " budget exhausted, retry later");
    }

    private long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /** Logs a completed request and records it in the lifetime stats (SPEC.md Section 7). */
    private void logCompleted(String model, boolean streaming, int inputTokens, int outputTokens, long startNanos) {
        long durationMillis = millisSince(startNanos);
        stats.recordCompletedRequest(model, streaming, inputTokens, outputTokens, durationMillis);
        SlidingWindowLimiter.Snapshot tpmSnapshot = tpmLimiter.snapshot();
        DailyTokenLimiter.Snapshot dailySnapshot = dailyLimiter.snapshot();
        System.out.printf(
                "tpm-proxy: model=%s stream=%b tokens=%d (in=%d out=%d) duration=%dms | window=%d/%d tpm | day=%d/%d | lifetime=%d tokens / %d requests%n",
                model, streaming, inputTokens + outputTokens, inputTokens, outputTokens, durationMillis,
                tpmSnapshot.windowUsage(), tpmSnapshot.limit(), dailySnapshot.usage(), dailySnapshot.limit(),
                stats.totalTokens(), stats.totalRequests());
    }

    private void logFailed(String model, boolean streaming, int upstreamStatus, long startNanos) {
        System.out.printf("tpm-proxy: model=%s stream=%b upstream_status=%d duration=%dms (no tokens charged)%n",
                model, streaming, upstreamStatus, millisSince(startNanos));
    }

    private void logRejected(String model, boolean streaming, String scope, long retryAfterMillis, long startNanos) {
        System.out.printf("tpm-proxy: model=%s stream=%b REJECTED (%s budget exhausted) retryAfter=%dms duration=%dms%n",
                model, streaming, scope, retryAfterMillis, millisSince(startNanos));
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

    private record Budget(Reservation tpm, DailyTokenLimiter.Reservation daily) {
    }
}
