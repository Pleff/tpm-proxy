package dev.tpmproxy.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import dev.tpmproxy.limiter.SlidingWindowLimiter;

import java.io.IOException;
import java.util.Map;

/**
 * GET/PUT /internal/limit - runtime-adjustable TPM budget (SPEC.md Section 5.4).
 * Changes take effect immediately and are in-memory only; a restart reverts
 * to the TPM_LIMIT startup value.
 */
public class LimitHandler implements HttpHandler {

    private final SlidingWindowLimiter limiter;
    private final ObjectMapper json;

    public LimitHandler(SlidingWindowLimiter limiter, ObjectMapper json) {
        this.limiter = limiter;
        this.json = json;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        if ("GET".equalsIgnoreCase(method)) {
            JsonHttp.writeJson(exchange, json, 200, Map.of("tpmLimit", limiter.tpmLimit()));
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

        if (body == null || !body.hasNonNull("tpmLimit") || !body.get("tpmLimit").isIntegralNumber()) {
            JsonHttp.writeAnthropicError(exchange, json, 400, "invalid_request_error", "tpmLimit (integer) is required");
            return;
        }

        int newLimit = body.get("tpmLimit").asInt();
        if (newLimit <= 0) {
            JsonHttp.writeAnthropicError(exchange, json, 400, "invalid_request_error", "tpmLimit must be positive");
            return;
        }

        int oldLimit = limiter.tpmLimit();
        limiter.setTpmLimit(newLimit);
        System.out.printf("tpm-proxy: TPM limit changed %d -> %d%n", oldLimit, newLimit);

        JsonHttp.writeJson(exchange, json, 200, Map.of("tpmLimit", newLimit));
    }
}
