package de.bestblu.tools.tpmproxy.upstream;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Preflight input-token estimation (SPEC.md Section 5.1). Langdock's
 * anthropic-compatible endpoint does not support /v1/messages/count_tokens
 * (confirmed via live test - 404 Not found), so this is a plain chars/4
 * heuristic, not a fallback for a primary call.
 *
 * <p>Anthropic prompt caching (a {@code cache_control} block) covers the
 * entire prefix up to and including the marked block - content there is
 * cheap to reuse once cached (observed in practice: ~18k estimated vs. 2
 * actual input_tokens for a cached system prompt). Since a preflight
 * estimate can't know whether a given cache entry is warm, content at or
 * before the last {@code cache_control} breakpoint is excluded rather than
 * counted at full (uncached-worst-case) price - counting it fully turns a
 * cheap, cached system prompt into a reservation that alone can exceed
 * TPM_LIMIT on every single request.
 */
public final class TokenEstimator {

    public int estimateInputTokens(JsonNode requestBody) {
        List<TextSource> sources = new ArrayList<>();
        collectSources(requestBody.path("system"), sources);

        JsonNode messages = requestBody.path("messages");
        if (messages.isArray()) {
            for (JsonNode message : messages) {
                collectSources(message.path("content"), sources);
            }
        }

        int lastCachedIndex = -1;
        for (int i = 0; i < sources.size(); i++) {
            if (sources.get(i).cached()) {
                lastCachedIndex = i;
            }
        }

        StringBuilder text = new StringBuilder();
        for (int i = lastCachedIndex + 1; i < sources.size(); i++) {
            text.append(sources.get(i).text());
        }

        int messageOverhead = messages.isArray() ? messages.size() * 4 : 0;
        return Math.max(1, text.length() / 4 + messageOverhead);
    }

    private void collectSources(JsonNode node, List<TextSource> out) {
        if (node.isTextual()) {
            // A plain string can't carry cache_control (that requires the block-array form).
            out.add(new TextSource(node.asText(), false));
        } else if (node.isArray()) {
            for (JsonNode block : node) {
                if (block.has("text")) {
                    out.add(new TextSource(block.path("text").asText(), block.has("cache_control")));
                }
            }
        }
    }

    private record TextSource(String text, boolean cached) {
    }
}
