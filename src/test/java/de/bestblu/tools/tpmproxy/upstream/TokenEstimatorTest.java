package de.bestblu.tools.tpmproxy.upstream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    void excludesACachedSystemPromptFromTheEstimate() throws Exception {
        // The whole (huge, in practice) system block carries cache_control - excluded entirely.
        // Only the fresh user message ("1234", 4 chars -> 1) plus 1 * 4 overhead = 5 counts.
        JsonNode body = mapper.readTree("""
                { "system": [
                    { "type": "text", "text": "a very large cached system prompt goes here", "cache_control": { "type": "ephemeral" } }
                  ],
                  "messages": [ { "role": "user", "content": "1234" } ] }
                """);

        assertEquals(5, estimator.estimateInputTokens(body));
    }

    @Test
    void countsFreshMessagesAfterTheLastCacheControlBreakpointNormally() throws Exception {
        // First message is behind the cache_control breakpoint (excluded); the second,
        // newer message comes after it and must still be counted at full price.
        // "5678" (4 chars -> 1) + 2 messages * 4 overhead = 9
        JsonNode body = mapper.readTree("""
                { "messages": [
                    { "role": "user", "content": [
                        { "type": "text", "text": "1234", "cache_control": { "type": "ephemeral" } }
                      ] },
                    { "role": "assistant", "content": "5678" }
                  ] }
                """);

        assertEquals(9, estimator.estimateInputTokens(body));
    }

    @Test
    void fallsBackToCountingEverythingWhenNothingIsCached() throws Exception {
        // No cache_control anywhere - behaves exactly like the plain heuristic.
        // "12345678" (8 chars -> 2) + 1 message * 4 overhead = 6
        JsonNode body = mapper.readTree("""
                { "messages": [ { "role": "user", "content": [
                    { "type": "text", "text": "12345678" }
                  ] } ] }
                """);

        assertEquals(6, estimator.estimateInputTokens(body));
    }
}
