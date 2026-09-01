package de.bestblu.tools.tpmproxy.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * GET / - HTML dashboard for live status and changing the TPM and/or daily
 * token limit (SPEC.md Section 7). Static page, loaded once at startup; all
 * data comes from client-side fetch() calls to /internal/status and
 * /internal/limit.
 */
public class DashboardHandler implements HttpHandler {

    private final byte[] page;

    public DashboardHandler() {
        try (InputStream in = getClass().getResourceAsStream("/dashboard.html")) {
            if (in == null) {
                throw new IllegalStateException("dashboard.html not found on classpath");
            }
            this.page = in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("failed to load dashboard.html", e);
        }
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }

        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, page.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(page);
        }
    }
}
