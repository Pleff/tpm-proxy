package de.bestblu.tools.tpmproxy.limiter;

import de.bestblu.tools.tpmproxy.limiter.SlidingWindowLimiter.Reservation;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlidingWindowLimiterTest {

    @Test
    void reservesWithinBudgetAndRejectsOverBudget() {
        SlidingWindowLimiter limiter = new SlidingWindowLimiter(1000, new MutableClock());

        assertTrue(limiter.tryReserve(600).isPresent());
        assertTrue(limiter.tryReserve(400).isPresent());
        assertFalse(limiter.tryReserve(1).isPresent(), "budget is fully spent, next reservation must be rejected");
    }

    @Test
    void admitsAnOversizedSingleRequestOnceTheWindowIsCompletelyEmpty() {
        SlidingWindowLimiter limiter = new SlidingWindowLimiter(1000, new MutableClock());

        // A single request bigger than the whole limit could never satisfy
        // currentSum + tokens <= limit, no matter how long it waits - it must
        // still be admitted (into an empty window) since a single API call
        // can't be split across windows.
        assertTrue(limiter.tryReserve(5000).isPresent(), "an oversized request must be admitted when nothing else is in flight");
        assertEquals(5000, limiter.snapshot().windowUsage());
    }

    @Test
    void oversizedRequestWaitsForTheWindowToClearRatherThanJumpingTheQueue() {
        SlidingWindowLimiter limiter = new SlidingWindowLimiter(1000, new MutableClock());

        limiter.tryReserve(200).orElseThrow(); // something else is already in flight

        assertFalse(limiter.tryReserve(5000).isPresent(),
                "an oversized request must not be admitted on top of existing usage, even though it can never fit normally");
    }

    @Test
    void correctFreesUpBudgetWhenActualUsageIsLower() {
        SlidingWindowLimiter limiter = new SlidingWindowLimiter(1000, new MutableClock());

        Reservation reservation = limiter.tryReserve(800).orElseThrow();
        assertFalse(limiter.tryReserve(500).isPresent());

        limiter.correct(reservation, 300); // actual usage was lower than the max_tokens-based reservation

        assertTrue(limiter.tryReserve(500).isPresent());
        assertEquals(800, limiter.snapshot().windowUsage());
        assertEquals(200, limiter.snapshot().remaining());
    }

    @Test
    void releaseDropsTheReservationEntirely() {
        SlidingWindowLimiter limiter = new SlidingWindowLimiter(1000, new MutableClock());

        Reservation reservation = limiter.tryReserve(1000).orElseThrow();
        assertFalse(limiter.tryReserve(1).isPresent());

        limiter.release(reservation);

        assertEquals(0, limiter.snapshot().windowUsage());
        assertTrue(limiter.tryReserve(1000).isPresent());
    }

    @Test
    void expiredEntriesFreeUpBudgetAfterTheWindowElapses() {
        MutableClock clock = new MutableClock();
        SlidingWindowLimiter limiter = new SlidingWindowLimiter(1000, clock);

        limiter.tryReserve(1000).orElseThrow();
        assertFalse(limiter.tryReserve(1).isPresent());

        clock.advance(Duration.ofSeconds(61));

        assertTrue(limiter.tryReserve(1000).isPresent(), "reservation should have aged out of the 60s window");
    }

    @Test
    void limitIsAdjustableAtRuntime() {
        SlidingWindowLimiter limiter = new SlidingWindowLimiter(100, new MutableClock());
        limiter.tryReserve(50).orElseThrow(); // something already in flight, leaving only 50 of the 100 limit free

        assertFalse(limiter.tryReserve(200).isPresent(), "200 does not fit in the remaining 50 of the 100 limit");

        limiter.setLimit(500);

        assertTrue(limiter.tryReserve(200).isPresent());
        assertEquals(500, limiter.snapshot().limit());
    }

    @Test
    void millisUntilAvailableIsZeroWhenBudgetIsFree() {
        SlidingWindowLimiter limiter = new SlidingWindowLimiter(1000, new MutableClock());

        assertEquals(0, limiter.millisUntilAvailable(500));
    }

    @Test
    void millisUntilAvailableEstimatesWhenTheOldestReservationExpires() {
        MutableClock clock = new MutableClock();
        SlidingWindowLimiter limiter = new SlidingWindowLimiter(1000, clock);

        limiter.tryReserve(1000).orElseThrow();

        long wait = limiter.millisUntilAvailable(500);

        assertTrue(wait > 0 && wait <= 60_000, "expected a wait roughly bounded by the 60s window, got " + wait);
    }

    @Test
    void pendingTokensTracksRequestsWaitingForBudget() throws InterruptedException {
        SlidingWindowLimiter limiter = new SlidingWindowLimiter(100, new MutableClock());
        limiter.tryReserve(100).orElseThrow(); // exhaust the budget so the next reserve has to wait

        assertEquals(0, limiter.snapshot().pendingTokens());

        Thread waiter = new Thread(() -> {
            try {
                limiter.reserveBlocking(50, 5_000);
            } catch (InterruptedException ignored) {
                // expected once the test interrupts it below
            }
        });
        waiter.start();
        try {
            long deadline = System.currentTimeMillis() + 2_000;
            while (limiter.snapshot().pendingTokens() == 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertEquals(50, limiter.snapshot().pendingTokens(), "waiting request should count as pending");
        } finally {
            waiter.interrupt();
            waiter.join(2_000);
        }

        assertEquals(0, limiter.snapshot().pendingTokens(), "pending count must clear once the waiter stops");
    }

    /** A {@link Clock} the test can advance manually to exercise window expiry. */
    private static final class MutableClock extends Clock {
        private Instant instant = Instant.parse("2026-01-01T00:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }
    }
}
