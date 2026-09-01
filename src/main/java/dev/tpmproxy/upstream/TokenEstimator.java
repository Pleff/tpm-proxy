package dev.tpmproxy.upstream;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Preflight input-token estimation (SPEC.md Section 5.1): try Langdock's
 * count_tokens endpoint first, fall back to a rough chars/4 heuristic if
 * that call fails for any reason.
 */
public final class TokenEstimator {

    private final LangdockClient client;

    public TokenEstimator(LangdockClient client) {
        this.client = client;
    }

    public int estimateInputTokens(JsonNode requestBody) {
        try {
            return client.countTokens(requestBody);
        } catch (Exception e) {
            System.err.println("tpm-proxy: count_tokens preflight failed, using heuristic fallback - " + e.getMessage());
            return heuristicEstimate(requestBody);
        }
    }

    private int heuristicEstimate(JsonNode requestBody) {
        StringBuilder text = new StringBuilder();
        appendText(requestBody.path("system"), text);

        JsonNode messages = requestBody.path("messages");
        if (messages.isArray()) {
            for (JsonNode message : messages) {
                appendText(message.path("content"), text);
            }
        }

        int messageOverhead = messages.isArray() ? messages.size() * 4 : 0;
        return Math.max(1, text.length() / 4 + messageOverhead);
    }

    private void appendText(JsonNode node, StringBuilder out) {
        if (node.isTextual()) {
            out.append(node.asText());
        } else if (node.isArray()) {
            for (JsonNode block : node) {
                if (block.has("text")) {
                    out.append(block.path("text").asText());
                }
            }
        }
    }
}
