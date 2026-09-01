package dev.tpmproxy.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import dev.tpmproxy.config.ProxyConfig;
import dev.tpmproxy.limiter.SlidingWindowLimiter;
import dev.tpmproxy.stats.ProxyStats;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** GET /internal/status - live TPM budget snapshot plus lifetime stats (SPEC.md Section 7). */
public class StatusHandler implements HttpHandler {

    private final ProxyConfig config;
    private final SlidingWindowLimiter limiter;
    private final ProxyStats stats;
    private final ObjectMapper json;

    public StatusHandler(ProxyConfig config, SlidingWindowLimiter limiter, ProxyStats stats, ObjectMapper json) {
        this.config = config;
        this.limiter = limiter;
        this.stats = stats;
        this.json = json;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }

        SlidingWindowLimiter.Snapshot snapshot = limiter.snapshot();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ok");
        body.put("tpmLimit", snapshot.tpmLimit());
        body.put("windowUsage", snapshot.windowUsage()); // current tokens/min - it IS the rate, by construction of the 60s window
        body.put("remaining", snapshot.remaining());
        body.put("activeReservations", snapshot.activeReservations());
        body.put("langdockBaseUrl", config.langdockBaseUrl());
        body.put("totalTokens", stats.totalTokens());
        body.put("totalRequests", stats.totalRequests());

        ProxyStats.LastRequest last = stats.lastRequest();
        if (last != null) {
            Map<String, Object> lastRequest = new LinkedHashMap<>();
            lastRequest.put("model", last.model());
            lastRequest.put("streaming", last.streaming());
            lastRequest.put("inputTokens", last.inputTokens());
            lastRequest.put("outputTokens", last.outputTokens());
            lastRequest.put("totalTokens", last.totalTokens());
            lastRequest.put("durationMillis", last.durationMillis());
            body.put("lastRequest", lastRequest);
        }

        JsonHttp.writeJson(exchange, json, 200, body);
    }
}
