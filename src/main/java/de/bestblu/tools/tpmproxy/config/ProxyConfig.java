package de.bestblu.tools.tpmproxy.config;

import java.util.Map;
import java.util.Optional;

/**
 * Startup configuration, read from environment variables (SPEC.md, Section 4).
 * {@code initialTpmLimit}/{@code initialDailyTokenLimit} are only the starting
 * values - the enforced runtime values live in the sliding-window limiters and
 * can be changed via PUT /internal/limit (or the dashboard) without a restart.
 */
public record ProxyConfig(
        String langdockApiKey,
        String langdockBaseUrl,
        int initialTpmLimit,
        int initialDailyTokenLimit,
        int proxyPort,
        String proxyClientToken,
        long queueTimeoutMs
) {
    private static final String DEFAULT_LANGDOCK_BASE_URL = "https://api.langdock.com/anthropic/eu";
    private static final int DEFAULT_PROXY_PORT = 8080;
    private static final long DEFAULT_QUEUE_TIMEOUT_MS = 30_000L;
    private static final int DEFAULT_TPM_LIMIT = 40_000;
    private static final int DEFAULT_MAX_TOKENS_PER_DAY = 1_000_000;

    public static ProxyConfig fromEnv() {
        return fromEnv(System.getenv());
    }

    public static ProxyConfig fromEnv(Map<String, String> env) {
        String langdockApiKey = requireNonBlank(env, "LANGDOCK_API_KEY");

        String langdockBaseUrl = optional(env, "LANGDOCK_BASE_URL").orElse(DEFAULT_LANGDOCK_BASE_URL);
        int tpmLimit = optional(env, "TPM_LIMIT")
                .map(v -> parsePositiveInt(v, "TPM_LIMIT")).orElse(DEFAULT_TPM_LIMIT);
        int dailyTokenLimit = optional(env, "MAX_TOKENS_PER_DAY")
                .map(v -> parsePositiveInt(v, "MAX_TOKENS_PER_DAY")).orElse(DEFAULT_MAX_TOKENS_PER_DAY);
        int proxyPort = optional(env, "PROXY_PORT")
                .map(v -> parsePositiveInt(v, "PROXY_PORT")).orElse(DEFAULT_PROXY_PORT);
        String proxyClientToken = optional(env, "PROXY_CLIENT_TOKEN").orElse(null);
        long queueTimeoutMs = optional(env, "QUEUE_TIMEOUT_MS").map(Long::parseLong).orElse(DEFAULT_QUEUE_TIMEOUT_MS);

        return new ProxyConfig(langdockApiKey, langdockBaseUrl, tpmLimit, dailyTokenLimit,
                proxyPort, proxyClientToken, queueTimeoutMs);
    }

    private static Optional<String> optional(Map<String, String> env, String key) {
        String value = env.get(key);
        return (value == null || value.isBlank()) ? Optional.empty() : Optional.of(value.trim());
    }

    private static String requireNonBlank(Map<String, String> env, String key) {
        return optional(env, key)
                .orElseThrow(() -> new ConfigException("Required environment variable %s is not set".formatted(key)));
    }

    private static int parsePositiveInt(String raw, String key) {
        int value;
        try {
            value = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new ConfigException("%s must be a valid integer, got: %s".formatted(key, raw));
        }
        if (value <= 0) {
            throw new ConfigException("%s must be a positive integer, got: %s".formatted(key, raw));
        }
        return value;
    }
}
