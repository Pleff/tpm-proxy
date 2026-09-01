package de.bestblu.tools.tpmproxy.stats;

import de.bestblu.tools.tpmproxy.stats.ProxyStats.LastRequest;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProxyStatsTest {

    @Test
    void startsEmptyWithNoRequestsRecorded() {
        ProxyStats stats = new ProxyStats();

        assertEquals(0, stats.totalTokens());
        assertEquals(0, stats.totalRequests());
        assertNull(stats.lastRequest());
    }

    @Test
    void recordsASingleCompletedRequest() {
        ProxyStats stats = new ProxyStats();

        stats.recordCompletedRequest("claude-3-5-sonnet", true, 100, 50, 1234L, "opencode", 80, 15, 5);

        assertEquals(150, stats.totalTokens());
        assertEquals(1, stats.totalRequests());

        LastRequest last = stats.lastRequest();
        assertEquals("claude-3-5-sonnet", last.model());
        assertTrue(last.streaming());
        assertEquals(100, last.inputTokens());
        assertEquals(50, last.outputTokens());
        assertEquals(150, last.totalTokens());
        assertEquals(1234L, last.durationMillis());
        assertEquals("opencode", last.client());
        assertTrue(last.timestampMillis() > 0);
        assertEquals(80, last.freshInputTokens());
        assertEquals(15, last.cacheCreationTokens());
        assertEquals(5, last.cacheReadTokens());
    }

    @Test
    void accumulatesTotalsAcrossMultipleRequests() {
        ProxyStats stats = new ProxyStats();

        stats.recordCompletedRequest("model-a", false, 10, 20, 100L, "client-a", 8, 1, 1);
        stats.recordCompletedRequest("model-b", true, 30, 40, 200L, "client-b", 25, 3, 2);

        assertEquals(100, stats.totalTokens()); // (10+20) + (30+40)
        assertEquals(2, stats.totalRequests());
    }

    @Test
    void lastRequestReflectsTheMostRecentCallOnly() {
        ProxyStats stats = new ProxyStats();

        stats.recordCompletedRequest("model-a", false, 10, 20, 100L, "client-a", 8, 1, 1);
        stats.recordCompletedRequest("model-b", true, 5, 5, 50L, "client-b", 4, 0, 1);

        LastRequest last = stats.lastRequest();
        assertEquals("model-b", last.model());
        assertEquals(10, last.totalTokens());
        assertEquals(50L, last.durationMillis());
    }

    @Test
    void isThreadSafeUnderConcurrentRecording() throws InterruptedException {
        ProxyStats stats = new ProxyStats();
        int threadCount = 8;
        int callsPerThread = 500;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            pool.submit(() -> {
                try {
                    for (int i = 0; i < callsPerThread; i++) {
                        stats.recordCompletedRequest("model", false, 1, 1, 1L, "client", 1, 0, 0);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS), "all threads should finish within the timeout");
        pool.shutdown();

        int expectedRequests = threadCount * callsPerThread;
        assertEquals(expectedRequests, stats.totalRequests());
        assertEquals(expectedRequests * 2, stats.totalTokens()); // 1 input + 1 output per call
    }
}
