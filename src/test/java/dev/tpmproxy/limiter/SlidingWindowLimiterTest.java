package dev.tpmproxy.limiter;

import dev.tpmproxy.limiter.SlidingWindowLimiter.Reservation;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlidingWindowLimiterTest {

    private static final long ONE_MINUTE_MILLIS = 60_000L;
    private static final long ONE_DAY_MILLIS = 24 * 60 * 60 * 1000L;

    @Test
    void reservesWithinBudgetAndRejectsOverBudget() {
        SlidingWindowLimiter limiter = new SlidingWindowLimiter(1000, ONE_MINUTE_MILLIS, new MutableClock());

        assertTrue(limiter.tryReserve(600).isPresent());
        assertTrue(limiter.tryReserve(400).isPresent());
        assertFalse(limiter.tryReserve(1).isPresent(), "budget is fully spent, next reservation must be rejected");
    }

    @Test
    void correctFreesUpBudgetWhenActualUsageIsLower() {
        SlidingWindowLimiter limiter = new SlidingWindowLimiter(1000, ONE_MINUTE_MILLIS, new MutableClock());

        Reservation reservation = limiter.tryReserve(800).orElseThrow();
        assertFalse(limiter.tryReserve(500).isPresent());

        limiter.correct(reservation, 300); // actual usage was lower than the max_tokens-based reservation

        assertTrue(limiter.tryReserve(500).isPresent());
        assertEquals(800, limiter.snapshot().windowUsage());
        assertEquals(200, limiter.snapshot().remaining());
    }

    @Test
    void releaseDropsTheReservationEntirely() {
        SlidingWindowLimiter limiter = new SlidingWindowLimiter(1000, ONE_MINUTE_MILLIS, new MutableClock());

        Reservation reservation = limiter.tryReserve(1000).orElseThrow();
        assertFalse(limiter.tryReserve(1).isPresent());

        limiter.release(reservation);

        assertEquals(0, limiter.snapshot().windowUsage());
        assertTrue(limiter.tryReserve(1000).isPresent());
    }

    @Test
    void expiredEntriesFreeUpBudgetAfterTheWindowElapses() {
        MutableClock clock = new MutableClock();
        SlidingWindowLimiter limiter = new SlidingWindowLimiter(1000, ONE_MINUTE_MILLIS, clock);

        limiter.tryReserve(1000).orElseThrow();
        assertFalse(limiter.tryReserve(1).isPresent());

        clock.advance(Duration.ofSeconds(61));

        assertTrue(limiter.tryReserve(1000).isPresent(), "reservation should have aged out of the 60s window");
    }

    @Test
    void limitIsAdjustableAtRuntime() {
        SlidingWindowLimiter limiter = new SlidingWindowLimiter(100, ONE_MINUTE_MILLIS, new MutableClock());

        assertFalse(limiter.tryReserve(200).isPresent());

        limiter.setLimit(500);

        assertTrue(limiter.tryReserve(200).isPresent());
        assertEquals(500, limiter.snapshot().limit());
    }

    @Test
    void millisUntilAvailableIsZeroWhenBudgetIsFree() {
        SlidingWindowLimiter limiter = new SlidingWindowLimiter(1000, ONE_MINUTE_MILLIS, new MutableClock());

        assertEquals(0, limiter.millisUntilAvailable(500));
    }

    @Test
    void millisUntilAvailableEstimatesWhenTheOldestReservationExpires() {
        MutableClock clock = new MutableClock();
        SlidingWindowLimiter limiter = new SlidingWindowLimiter(1000, ONE_MINUTE_MILLIS, clock);

        limiter.tryReserve(1000).orElseThrow();

        long wait = limiter.millisUntilAvailable(500);

        assertTrue(wait > 0 && wait <= ONE_MINUTE_MILLIS, "expected a wait roughly bounded by the 60s window, got " + wait);
    }

    @Test
    void supportsAMuchLongerWindowForTheDailyBudget() {
        MutableClock clock = new MutableClock();
        SlidingWindowLimiter dailyLimiter = new SlidingWindowLimiter(1_000_000, ONE_DAY_MILLIS, clock);

        dailyLimiter.tryReserve(1_000_000).orElseThrow();
        assertFalse(dailyLimiter.tryReserve(1).isPresent());

        clock.advance(Duration.ofHours(23).plusMinutes(59));
        assertFalse(dailyLimiter.tryReserve(1).isPresent(), "24h window should not have elapsed yet");

        clock.advance(Duration.ofMinutes(2));
        assertTrue(dailyLimiter.tryReserve(1_000_000).isPresent(), "24h window should have elapsed by now");
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
