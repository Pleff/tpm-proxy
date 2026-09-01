package de.bestblu.tools.tpmproxy.upstream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenEstimatorTest {

    private final TokenEstimator estimator = new TokenEstimator();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void returnsAtLeastOneTokenForAnEmptyRequest() throws Exception {
        JsonNode body = mapper.readTree("{}");

        assertEquals(1, estimator.estimateInputTokens(body));
    }

    @Test
    void estimatesFromSystemPromptTextAlone() throws Exception {
        // 12 chars -> 12/4 = 3, no messages -> no overhead
        JsonNode body = mapper.readTree("""
                { "system": "123456789012" }
                """);

        assertEquals(3, estimator.estimateInputTokens(body));
    }

    @Test
    void estimatesFromSimpleStringMessageContent() throws Exception {
        // 8 chars -> 8/4 = 2, plus 1 message * 4 overhead = 6
        JsonNode body = mapper.readTree("""
                { "messages": [ { "role": "user", "content": "12345678" } ] }
                """);

        assertEquals(6, estimator.estimateInputTokens(body));
    }

    @Test
    void estimatesFromArrayContentBlocksWithTextField() throws Exception {
        // "1234" + "5678" = 8 chars -> 8/4 = 2, plus 1 message * 4 overhead = 6
        JsonNode body = mapper.readTree("""
                { "messages": [ { "role": "user", "content": [
                    { "type": "text", "text": "1234" },
                    { "type": "text", "text": "5678" }
                  ] } ] }
                """);

        assertEquals(6, estimator.estimateInputTokens(body));
    }

    @Test
    void ignoresContentBlocksWithoutATextField() throws Exception {
        // no text contributes 0 chars, but the message overhead (1 * 4) still applies
        JsonNode body = mapper.readTree("""
                { "messages": [ { "role": "user", "content": [
                    { "type": "image", "source": { "type": "base64", "data": "xxxx" } }
                  ] } ] }
                """);

        assertEquals(4, estimator.estimateInputTokens(body));
    }

    @Test
    void accumulatesOverheadForEachMessageInTheConversation() throws Exception {
        // "ab" + "cd" + "ef" = 6 chars -> 6/4 = 1, plus 3 messages * 4 overhead = 13
        JsonNode body = mapper.readTree("""
                { "messages": [
                    { "role": "user", "content": "ab" },
                    { "role": "assistant", "content": "cd" },
                    { "role": "user", "content": "ef" }
                  ] }
                """);

        assertEquals(13, estimator.estimateInputTokens(body));
    }

    @Test
    void combinesSystemPromptAndMessageTextTogether() throws Exception {
        // system "12345678" (8 chars) + message "1234" (4 chars) = 12 chars -> 12/4 = 3, plus 1 * 4 overhead = 7
        JsonNode body = mapper.readTree("""
                { "system": "12345678", "messages": [ { "role": "user", "content": "1234" } ] }
                """);

        assertEquals(7, estimator.estimateInputTokens(body));
    }

    @Test
    void stillCountsACachedSystemPromptAtFullPrice() throws Exception {
        // cache_control lowers billing (usage.input_tokens), not what Langdock's real TPM
        // enforcement counts (SPEC.md Section 5.1) - so the estimate must NOT exclude it.
        // system "a very large cached system prompt goes here" (43 chars) + message "1234"
        // (4 chars) = 47 chars -> 47/4 = 11, plus 1 message * 4 overhead = 15
        JsonNode body = mapper.readTree("""
                { "system": [
                    { "type": "text", "text": "a very large cached system prompt goes here", "cache_control": { "type": "ephemeral" } }
                  ],
                  "messages": [ { "role": "user", "content": "1234" } ] }
                """);

        assertEquals(15, estimator.estimateInputTokens(body));
    }

    @Test
    void cacheControlAnywhereInTheConversationDoesNotAffectTheEstimate() throws Exception {
        // "1234" + "5678" = 8 chars -> 2, plus 2 messages * 4 overhead = 10 - identical to
        // the same content without any cache_control marker.
        JsonNode body = mapper.readTree("""
                { "messages": [
                    { "role": "user", "content": [
                        { "type": "text", "text": "1234", "cache_control": { "type": "ephemeral" } }
                      ] },
                    { "role": "assistant", "content": "5678" }
                  ] }
                """);

        assertEquals(10, estimator.estimateInputTokens(body));
    }

    @Test
    void hasCacheControlIsTrueWhenAnyBlockCarriesIt() throws Exception {
        JsonNode body = mapper.readTree("""
                { "system": [
                    { "type": "text", "text": "cached prompt", "cache_control": { "type": "ephemeral" } }
                  ] }
                """);

        assertTrue(estimator.hasCacheControl(body));
    }

    @Test
    void hasCacheControlIsFalseWhenNoBlockCarriesIt() throws Exception {
        JsonNode body = mapper.readTree("""
                { "system": "plain string prompt",
                  "messages": [ { "role": "user", "content": [
                      { "type": "text", "text": "no cache marker here" }
                    ] } ] }
                """);

        assertFalse(estimator.hasCacheControl(body));
    }

    @Test
    void diagnosticsReportsStructuralCountsAroundTheCacheBreakpoint() throws Exception {
        JsonNode body = mapper.readTree("""
                { "system": [
                    { "type": "text", "text": "cached system prompt", "cache_control": { "type": "ephemeral" } }
                  ],
                  "messages": [ { "role": "user", "content": "fresh new turn" } ] }
                """);

        String diagnostics = estimator.diagnostics(body);

        assertTrue(diagnostics.contains("sources=2"), diagnostics);
        assertTrue(diagnostics.contains("cachedThrough=0"), diagnostics);
        assertTrue(diagnostics.contains("includedChars=14"), diagnostics); // "fresh new turn".length()
        assertTrue(diagnostics.contains("excludedChars=20"), diagnostics); // "cached system prompt".length()
    }

    @Test
    void diagnosticsNeverIncludesActualMessageOrPromptContent() throws Exception {
        // Structural counts only - no preview/content field, so private conversation
        // text can never end up in logs via this method.
        JsonNode body = mapper.readTree("""
                { "system": "a secret system prompt nobody should see in logs",
                  "messages": [ { "role": "user", "content": "another private message" } ] }
                """);

        String diagnostics = estimator.diagnostics(body);

        assertFalse(diagnostics.contains("secret"));
        assertFalse(diagnostics.contains("private"));
        assertFalse(diagnostics.contains("preview"));
    }
}
