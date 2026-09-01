package dev.tpmproxy.config;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProxyConfigTest {

    @Test
    void throwsWhenRequiredApiKeyIsMissing() {
        Map<String, String> env = new HashMap<>();

        ConfigException exception = assertThrows(ConfigException.class, () -> ProxyConfig.fromEnv(env));
        assertEquals("Required environment variable LANGDOCK_API_KEY is not set", exception.getMessage());
    }

    @Test
    void throwsWhenRequiredApiKeyIsBlank() {
        Map<String, String> env = Map.of("LANGDOCK_API_KEY", "   ");

        assertThrows(ConfigException.class, () -> ProxyConfig.fromEnv(env));
    }

    @Test
    void appliesDefaultsWhenOnlyRequiredFieldsAreSet() {
        Map<String, String> env = Map.of("LANGDOCK_API_KEY", "secret-key");

        ProxyConfig config = ProxyConfig.fromEnv(env);

        assertEquals("secret-key", config.langdockApiKey());
        assertEquals("https://api.langdock.com/anthropic/eu", config.langdockBaseUrl());
        assertEquals(40_000, config.initialTpmLimit());
        assertEquals(1_000_000, config.initialDailyTokenLimit());
        assertEquals(8080, config.proxyPort());
        assertNull(config.proxyClientToken());
        assertEquals(30_000L, config.queueTimeoutMs());
    }

    @Test
    void overridesDefaultsWithProvidedValues() {
        Map<String, String> env = new HashMap<>();
        env.put("LANGDOCK_API_KEY", "secret-key");
        env.put("LANGDOCK_BASE_URL", "https://example.invalid/anthropic");
        env.put("TPM_LIMIT", "12345");
        env.put("MAX_TOKENS_PER_DAY", "500000");
        env.put("PROXY_PORT", "9090");
        env.put("PROXY_CLIENT_TOKEN", "client-token");
        env.put("QUEUE_TIMEOUT_MS", "5000");

        ProxyConfig config = ProxyConfig.fromEnv(env);

        assertEquals("https://example.invalid/anthropic", config.langdockBaseUrl());
        assertEquals(12345, config.initialTpmLimit());
        assertEquals(500000, config.initialDailyTokenLimit());
        assertEquals(9090, config.proxyPort());
        assertEquals("client-token", config.proxyClientToken());
        assertEquals(5000L, config.queueTimeoutMs());
    }

    @Test
    void treatsBlankOptionalValuesAsAbsentAndFallsBackToDefaults() {
        Map<String, String> env = new HashMap<>();
        env.put("LANGDOCK_API_KEY", "secret-key");
        env.put("LANGDOCK_BASE_URL", "   ");
        env.put("PROXY_CLIENT_TOKEN", "");

        ProxyConfig config = ProxyConfig.fromEnv(env);

        assertEquals("https://api.langdock.com/anthropic/eu", config.langdockBaseUrl());
        assertNull(config.proxyClientToken());
    }

    @Test
    void throwsWhenNumericFieldIsNotAValidInteger() {
        Map<String, String> env = new HashMap<>();
        env.put("LANGDOCK_API_KEY", "secret-key");
        env.put("TPM_LIMIT", "not-a-number");

        ConfigException exception = assertThrows(ConfigException.class, () -> ProxyConfig.fromEnv(env));
        assertEquals("TPM_LIMIT must be a valid integer, got: not-a-number", exception.getMessage());
    }

    @Test
    void throwsWhenNumericFieldIsZeroOrNegative() {
        Map<String, String> env = new HashMap<>();
        env.put("LANGDOCK_API_KEY", "secret-key");
        env.put("PROXY_PORT", "0");

        ConfigException exception = assertThrows(ConfigException.class, () -> ProxyConfig.fromEnv(env));
        assertEquals("PROXY_PORT must be a positive integer, got: 0", exception.getMessage());
    }

    @Test
    void trimsWhitespaceAroundStringValues() {
        Map<String, String> env = new HashMap<>();
        env.put("LANGDOCK_API_KEY", "  secret-key  ");
        env.put("PROXY_CLIENT_TOKEN", "  client-token  ");

        ProxyConfig config = ProxyConfig.fromEnv(env);

        assertEquals("secret-key", config.langdockApiKey());
        assertEquals("client-token", config.proxyClientToken());
    }
}
