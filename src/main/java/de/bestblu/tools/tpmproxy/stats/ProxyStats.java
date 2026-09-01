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

    public void recordCompletedRequest(String model, boolean streaming, int inputTokens, int outputTokens,
                                        long durationMillis, String client) {
        int tokens = inputTokens + outputTokens;
        totalTokens.addAndGet(tokens);
        totalRequests.incrementAndGet();
        lastRequest.set(new LastRequest(model, streaming, inputTokens, outputTokens, tokens, durationMillis,
                client, System.currentTimeMillis()));
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
            String client, long timestampMillis) {
    }
}
