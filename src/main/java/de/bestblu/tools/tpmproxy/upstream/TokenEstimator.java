package de.bestblu.tools.tpmproxy.upstream;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Preflight input-token estimation (SPEC.md Section 5.1). Langdock's
 * anthropic-compatible endpoint does not support /v1/messages/count_tokens
 * (confirmed via live test - 404 Not found), so this is a plain chars/4
 * heuristic, not a fallback for a primary call.
 */
public final class TokenEstimator {

    public int estimateInputTokens(JsonNode requestBody) {
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
