package dev.tpmproxy.config;

import java.util.Map;
import java.util.Optional;

/**
 * Startup configuration, read from environment variables (SPEC.md, Section 4).
 * {@code tpmLimit} is only the initial value - the enforced runtime value lives
 * in the sliding-window limiter and can be changed via PUT /internal/limit.
 */
public record ProxyConfig(
        String langdockApiKey,
        String langdockBaseUrl,
        int initialTpmLimit,
        int proxyPort,
        String proxyClientToken,
        long queueTimeoutMs
) {
    private static final String DEFAULT_LANGDOCK_BASE_URL = "https://api.langdock.com/anthropic/eu";
    private static final int DEFAULT_PROXY_PORT = 8080;
    private static final long DEFAULT_QUEUE_TIMEOUT_MS = 30_000L;

    public static ProxyConfig fromEnv() {
        return fromEnv(System.getenv());
    }

    public static ProxyConfig fromEnv(Map<String, String> env) {
        String langdockApiKey = requireNonBlank(env, "LANGDOCK_API_KEY");
        int tpmLimit = requirePositiveInt(env, "TPM_LIMIT");

        String langdockBaseUrl = optional(env, "LANGDOCK_BASE_URL").orElse(DEFAULT_LANGDOCK_BASE_URL);
        int proxyPort = optional(env, "PROXY_PORT").map(ProxyConfig::parseInt).orElse(DEFAULT_PROXY_PORT);
        String proxyClientToken = optional(env, "PROXY_CLIENT_TOKEN").orElse(null);
        long queueTimeoutMs = optional(env, "QUEUE_TIMEOUT_MS").map(Long::parseLong).orElse(DEFAULT_QUEUE_TIMEOUT_MS);

        return new ProxyConfig(langdockApiKey, langdockBaseUrl, tpmLimit, proxyPort, proxyClientToken, queueTimeoutMs);
    }

    private static Optional<String> optional(Map<String, String> env, String key) {
        String value = env.get(key);
        return (value == null || value.isBlank()) ? Optional.empty() : Optional.of(value.trim());
    }

    private static String requireNonBlank(Map<String, String> env, String key) {
        return optional(env, key)
                .orElseThrow(() -> new ConfigException("Required environment variable %s is not set".formatted(key)));
    }

    private static int requirePositiveInt(Map<String, String> env, String key) {
        String raw = requireNonBlank(env, key);
        int value = parseInt(raw, key);
        if (value <= 0) {
            throw new ConfigException("%s must be a positive integer, got: %s".formatted(key, raw));
        }
        return value;
    }

    private static int parseInt(String raw) {
        return parseInt(raw, "value");
    }

    private static int parseInt(String raw, String key) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new ConfigException("%s must be a valid integer, got: %s".formatted(key, raw));
        }
    }
}
