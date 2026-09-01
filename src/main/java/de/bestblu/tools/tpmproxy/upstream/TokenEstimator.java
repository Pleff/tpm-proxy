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
 * <p><b>Cached content is counted at full price, not excluded.</b> An
 * earlier version excluded content at or before a {@code cache_control}
 * breakpoint, since it's billed cheaply once cached
 * ({@code usage.input_tokens} stayed near zero on repeat requests).
 * Live evidence overturned that: Langdock returned real upstream 429s
 * ("&gt;250000 TPM") while local accounting - which only tallied
 * {@code input_tokens} - showed the budget nowhere near exhausted.
 * Caching appears to reduce cost, not the compute Langdock's real TPM
 * enforcement counts - the reused context still gets reprocessed every
 * request. Counting it here means the proxy's own admission control can
 * actually catch that before Langdock does, at the cost of needing a
 * TPM_LIMIT that reflects the real workspace limit rather than a small
 * default (SPEC.md Section 5.1).
 *
 * <p>On top of the chars/4 heuristic, a flat overhead of 4 tokens per message
 * in the {@code messages} array is added (see {@link #estimateInputTokens}) to
 * account for Anthropic's per-message role/structure tokens, which the plain
 * character count of the message content alone would miss.
 */
public final class TokenEstimator {

    public int estimateInputTokens(JsonNode requestBody) {
        Analysis analysis = analyze(requestBody);
        int messageOverhead = analysis.messageCount() * 4;
        return Math.max(1, analysis.totalChars() / 4 + messageOverhead);
    }

    /**
     * Diagnostic only: whether any content block in the request carries
     * {@code cache_control} - lets callers log this alongside the estimate to
     * tell apart "client didn't request caching" from "estimator missed it"
     * when a reservation looks too large despite low actual usage.
     */
    public boolean hasCacheControl(JsonNode requestBody) {
        return analyze(requestBody).lastCachedIndex() >= 0;
    }

    /**
     * Diagnostic: structural counts (how many content sources were seen,
     * where the cache breakpoint sits, how many characters were counted
     * before/after it) plus a short preview of the "fresh" (post-breakpoint)
     * content that's actually driving the estimate - lets a human eyeball
     * whether that content genuinely looks like new conversation or is
     * mistakenly still part of what should have been excluded as cached.
     */
    public String diagnostics(JsonNode requestBody) {
        Analysis a = analyze(requestBody);
        String flattened = a.includedText().replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ');
        String preview = flattened.length() > 200 ? flattened.substring(0, 200) + "..." : flattened;
        return "sources=%d cachedThrough=%d includedChars=%d excludedChars=%d includedPreview=\"%s\"".formatted(
                a.totalSources(), a.lastCachedIndex(), a.includedChars(), a.totalChars() - a.includedChars(), preview);
    }

    private Analysis analyze(JsonNode requestBody) {
        List<TextSource> sources = new ArrayList<>();
        collectSources(requestBody.path("system"), sources);

        JsonNode messages = requestBody.path("messages");
        int messageCount = 0;
        if (messages.isArray()) {
            messageCount = messages.size();
            for (JsonNode message : messages) {
                collectSources(message.path("content"), sources);
            }
        }

        int lastCachedIndex = -1;
        int totalChars = 0;
        for (int i = 0; i < sources.size(); i++) {
            totalChars += sources.get(i).text().length();
            if (sources.get(i).cached()) {
                lastCachedIndex = i;
            }
        }

        StringBuilder includedText = new StringBuilder();
        for (int i = lastCachedIndex + 1; i < sources.size(); i++) {
            includedText.append(sources.get(i).text());
        }

        return new Analysis(sources.size(), lastCachedIndex, includedText.length(), totalChars, messageCount, includedText.toString());
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

    private record Analysis(
            int totalSources, int lastCachedIndex, int includedChars, int totalChars, int messageCount, String includedText) {
    }
}
