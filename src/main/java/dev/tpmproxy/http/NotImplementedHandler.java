package dev.tpmproxy.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import dev.tpmproxy.Main;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Placeholder for POST /v1/messages until budget enforcement and Langdock
 * forwarding (SPEC.md Sections 5-6) are implemented.
 */
public class NotImplementedHandler implements HttpHandler {

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("type", "not_implemented");
        details.put("message", "tpm-proxy: request forwarding not implemented yet");

        Map<String, Object> error = new LinkedHashMap<>();
        error.put("type", "error");
        error.put("error", details);

        byte[] payload = Main.JSON.writeValueAsBytes(error);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(501, payload.length);
        try (var os = exchange.getResponseBody()) {
            os.write(payload);
        }
    }
}
