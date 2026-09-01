package de.bestblu.tools.tpmproxy.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import de.bestblu.tools.tpmproxy.Version;
import de.bestblu.tools.tpmproxy.config.ProxyConfig;
import de.bestblu.tools.tpmproxy.limiter.DailyTokenLimiter;
import de.bestblu.tools.tpmproxy.limiter.SlidingWindowLimiter;
import de.bestblu.tools.tpmproxy.stats.ProxyStats;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** GET /internal/status - live TPM + daily budget snapshot plus lifetime stats (SPEC.md Section 7). */
public class StatusHandler implements HttpHandler {

    private final ProxyConfig config;
    private final SlidingWindowLimiter tpmLimiter;
    private final DailyTokenLimiter dailyLimiter;
    private final ProxyStats stats;
    private final ObjectMapper json;

    public StatusHandler(ProxyConfig config, SlidingWindowLimiter tpmLimiter, DailyTokenLimiter dailyLimiter,
                          ProxyStats stats, ObjectMapper json) {
        this.config = config;
        this.tpmLimiter = tpmLimiter;
        this.dailyLimiter = dailyLimiter;
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

        SlidingWindowLimiter.Snapshot tpmSnapshot = tpmLimiter.snapshot();
        DailyTokenLimiter.Snapshot dailySnapshot = dailyLimiter.snapshot();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ok");
        body.put("version", Version.get());
        body.put("tpmLimit", tpmSnapshot.limit());
        body.put("windowUsage", tpmSnapshot.windowUsage()); // current tokens/min - it IS the rate, by construction of the 60s window
        body.put("remaining", tpmSnapshot.remaining());
        body.put("activeReservations", tpmSnapshot.activeReservations());
        body.put("dailyLimit", dailySnapshot.limit());
        body.put("dailyUsage", dailySnapshot.usage()); // resets to 0 at local midnight, not a rolling window
        body.put("dailyRemaining", dailySnapshot.remaining());
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
            lastRequest.put("client", last.client());
            lastRequest.put("timestampMillis", last.timestampMillis());
            body.put("lastRequest", lastRequest);
        }

        JsonHttp.writeJson(exchange, json, 200, body);
    }
}
