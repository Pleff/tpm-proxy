package dev.tpmproxy.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import dev.tpmproxy.Main;
import dev.tpmproxy.config.ProxyConfig;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GET /internal/status - minimal health/status placeholder.
 * TODO(SPEC.md Section 7): replace tpmLimit/window usage with live values once
 * the sliding-window limiter (Section 5.2) and PUT /internal/limit (Section 5.4)
 * are implemented.
 */
public class StatusHandler implements HttpHandler {

    private final ProxyConfig config;

    public StatusHandler(ProxyConfig config) {
        this.config = config;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ok");
        body.put("tpmLimit", config.initialTpmLimit());
        body.put("langdockBaseUrl", config.langdockBaseUrl());

        byte[] payload = Main.JSON.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, payload.length);
        try (var os = exchange.getResponseBody()) {
            os.write(payload);
        }
    }
}
