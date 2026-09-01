package de.bestblu.tools.tpmproxy.stats;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lifetime counters and a last-request snapshot for observability
 * (separate from {@link de.bestblu.tools.tpmproxy.limiter.SlidingWindowLimiter}, which
 * only tracks the rolling 60s budget, not history).
 */
public final class ProxyStats {

    private final AtomicLong totalTokens = new AtomicLong();
    private final AtomicLong totalRequests = new AtomicLong();
    private final AtomicReference<LastRequest> lastRequest = new AtomicReference<>();

    /**
     * @param inputTokens fresh + cache_creation + cache_read folded together - the total used
     *                    for TPM/daily budget accounting (SPEC.md Section 5.1).
     * @param freshInputTokens the same three components kept separate for cost visibility only
     * @param cacheCreationTokens (SPEC.md Section 7) - not used for any budget math, just logged/exposed
     * @param cacheReadTokens as-is, since the three have very different $/token prices.
     */
    public void recordCompletedRequest(String model, boolean streaming, int inputTokens, int outputTokens,
                                        long durationMillis, String client,
                                        int freshInputTokens, int cacheCreationTokens, int cacheReadTokens) {
        int tokens = inputTokens + outputTokens;
        totalTokens.addAndGet(tokens);
        totalRequests.incrementAndGet();
        lastRequest.set(new LastRequest(model, streaming, inputTokens, outputTokens, tokens, durationMillis,
                client, System.currentTimeMillis(), freshInputTokens, cacheCreationTokens, cacheReadTokens));
    }

    public long totalTokens() {
        return totalTokens.get();
    }

    public long totalRequests() {
        return totalRequests.get();
    }

    public LastRequest lastRequest() {
        return lastRequest.get();
    }

    public record LastRequest(
            String model, boolean streaming, int inputTokens, int outputTokens, int totalTokens, long durationMillis,
            String client, long timestampMillis, int freshInputTokens, int cacheCreationTokens, int cacheReadTokens) {
    }
}
