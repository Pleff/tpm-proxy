package dev.tpmproxy.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import dev.tpmproxy.limiter.SlidingWindowLimiter;

import java.io.IOException;
import java.util.Map;

/**
 * GET/PUT /internal/limit - runtime-adjustable TPM and daily token budgets
 * (SPEC.md Section 5.4). A PUT may set either or both of {@code tpmLimit} /
 * {@code dailyLimit}; whichever field is present gets updated. Changes take
 * effect immediately and are in-memory only; a restart reverts to the
 * TPM_LIMIT / MAX_TOKENS_PER_DAY startup values.
 */
public class LimitHandler implements HttpHandler {

    private final SlidingWindowLimiter tpmLimiter;
    private final SlidingWindowLimiter dailyLimiter;
    private final ObjectMapper json;

    public LimitHandler(SlidingWindowLimiter tpmLimiter, SlidingWindowLimiter dailyLimiter, ObjectMapper json) {
        this.tpmLimiter = tpmLimiter;
        this.dailyLimiter = dailyLimiter;
        this.json = json;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        if ("GET".equalsIgnoreCase(method)) {
            writeCurrentLimits(exchange);
            return;
        }
        if (!"PUT".equalsIgnoreCase(method)) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }

        JsonNode body;
        try {
            body = json.readTree(exchange.getRequestBody());
        } catch (IOException e) {
            JsonHttp.writeAnthropicError(exchange, json, 400, "invalid_request_error", "malformed JSON body");
            return;
        }

        boolean hasTpm = body != null && body.hasNonNull("tpmLimit");
        boolean hasDaily = body != null && body.hasNonNull("dailyLimit");
        if (!hasTpm && !hasDaily) {
            JsonHttp.writeAnthropicError(exchange, json, 400, "invalid_request_error",
                    "tpmLimit and/or dailyLimit (integer) required");
            return;
        }
        if (hasTpm && !isPositiveInteger(body.get("tpmLimit"))) {
            JsonHttp.writeAnthropicError(exchange, json, 400, "invalid_request_error", "tpmLimit must be a positive integer");
            return;
        }
        if (hasDaily && !isPositiveInteger(body.get("dailyLimit"))) {
            JsonHttp.writeAnthropicError(exchange, json, 400, "invalid_request_error", "dailyLimit must be a positive integer");
            return;
        }

        if (hasTpm) {
            int newLimit = body.get("tpmLimit").asInt();
            int oldLimit = tpmLimiter.limit();
            tpmLimiter.setLimit(newLimit);
            System.out.printf("tpm-proxy: TPM limit changed %d -> %d%n", oldLimit, newLimit);
        }
        if (hasDaily) {
            int newLimit = body.get("dailyLimit").asInt();
            int oldLimit = dailyLimiter.limit();
            dailyLimiter.setLimit(newLimit);
            System.out.printf("tpm-proxy: daily token limit changed %d -> %d%n", oldLimit, newLimit);
        }

        writeCurrentLimits(exchange);
    }

    private boolean isPositiveInteger(JsonNode node) {
        return node.isIntegralNumber() && node.asInt() > 0;
    }

    private void writeCurrentLimits(HttpExchange exchange) throws IOException {
        JsonHttp.writeJson(exchange, json, 200, Map.of(
                "tpmLimit", tpmLimiter.limit(),
                "dailyLimit", dailyLimiter.limit()));
    }
}
