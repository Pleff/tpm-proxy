package de.bestblu.tools.tpmproxy.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Map;

/** Small shared helpers so every handler emits JSON the same way. */
final class JsonHttp {

    private JsonHttp() {
    }

    static void writeJson(HttpExchange exchange, ObjectMapper json, int status, Object body) throws IOException {
        byte[] payload = json.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(payload);
        }
    }

    /** Anthropic-shaped error body: {"type":"error","error":{"type":..., "message":...}} (SPEC.md Section 5.3). */
    static void writeAnthropicError(HttpExchange exchange, ObjectMapper json, int status, String type, String message)
            throws IOException {
        writeJson(exchange, json, status, Map.of("type", "error", "error", Map.of("type", type, "message", message)));
    }
}
