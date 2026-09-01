package de.bestblu.tools.tpmproxy.limiter;

import de.bestblu.tools.tpmproxy.limiter.DailyTokenLimiter.Reservation;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DailyTokenLimiterTest {

    @Test
    void reservingAloneDoesNotMoveTheVisibleTotal() {
        DailyTokenLimiter limiter = new DailyTokenLimiter(1000, new MutableClock());

        limiter.tryReserve(800).orElseThrow();

        assertEquals(0, limiter.snapshot().usage(), "a reservation is only a worst-case admission check, not committed usage");
    }

    @Test
    void usageOnlyEverIncreasesAndNeverDropsBackDownAfterCorrection() {
        DailyTokenLimiter limiter = new DailyTokenLimiter(1000, new MutableClock());

        Reservation first = limiter.tryReserve(800).orElseThrow(); // worst-case max_tokens estimate
        limiter.correct(first, 50); // actual usage turned out much lower than the reservation
        assertEquals(50, limiter.snapshot().usage());

        Reservation second = limiter.tryReserve(700).orElseThrow();
        limiter.correct(second, 30);
        assertEquals(80, limiter.snapshot().usage(), "usage must accumulate real usage only, never decrease");
    }

    @Test
    void rejectsWhenCommittedUsagePlusWorstCaseWouldExceedBudget() {
        DailyTokenLimiter limiter = new DailyTokenLimiter(1000, new MutableClock());

        Reservation reservation = limiter.tryReserve(900).orElseThrow();
        limiter.correct(reservation, 900); // commit the full worst case as real usage

        assertFalse(limiter.tryReserve(200).isPresent(), "900 committed + 200 estimate would exceed 1000");
        assertTrue(limiter.tryReserve(100).isPresent());
    }

    @Test
    void releaseRecordsNoUsage() {
        DailyTokenLimiter limiter = new DailyTokenLimiter(1000, new MutableClock());

        Reservation reservation = limiter.tryReserve(1000).orElseThrow();
        limiter.release(reservation);

        assertEquals(0, limiter.snapshot().usage());
        assertTrue(limiter.tryReserve(1000).isPresent());
    }

    @Test
    void doesNotResetJustBeforeMidnight() {
        // 23:59:59 CET - still the same calendar day.
        MutableClock clock = new MutableClock(Instant.parse("2026-03-04T22:59:59Z"));
        DailyTokenLimiter limiter = new DailyTokenLimiter(1000, clock);

        Reservation reservation = limiter.tryReserve(1000).orElseThrow();
        limiter.correct(reservation, 1000);

        assertFalse(limiter.tryReserve(1).isPresent(), "still the same calendar day, budget should stay exhausted");
    }

    @Test
    void resetsAtLocalMidnightRegardlessOfWhenTheLastReservationWasMade() {
        MutableClock clock = new MutableClock(Instant.parse("2026-03-04T22:00:00Z")); // 23:00 CET
        DailyTokenLimiter limiter = new DailyTokenLimiter(1000, clock);

        Reservation reservation = limiter.tryReserve(1000).orElseThrow();
        limiter.correct(reservation, 1000);
        assertFalse(limiter.tryReserve(1).isPresent());

        clock.set(Instant.parse("2026-03-04T23:30:00Z")); // 00:30 CET the next day - past local midnight
        assertTrue(limiter.tryReserve(1000).isPresent(), "budget should have reset at local midnight");
    }

    @Test
    void correctionAfterMidnightDoesNotLeakIntoTheNewDay() {
        MutableClock clock = new MutableClock(Instant.parse("2026-03-04T22:59:00Z")); // 23:59 CET
        DailyTokenLimiter limiter = new DailyTokenLimiter(1000, clock);

        Reservation yesterday = limiter.tryReserve(500).orElseThrow(); // never corrected before midnight

        clock.set(Instant.parse("2026-03-04T23:05:00Z")); // 00:05 CET next day
        Reservation today = limiter.tryReserve(200).orElseThrow();
        limiter.correct(today, 200);

        limiter.correct(yesterday, 999); // late correction for a reservation from the previous day
        assertEquals(200, limiter.snapshot().usage(), "yesterday's reservation must not affect today's total");
    }

    @Test
    void millisUntilAvailableIsZeroWhenBudgetIsFree() {
        DailyTokenLimiter limiter = new DailyTokenLimiter(1000, new MutableClock());

        assertEquals(0, limiter.millisUntilAvailable(500));
    }

    @Test
    void millisUntilAvailableCountsDownToLocalMidnight() {
        MutableClock clock = new MutableClock(Instant.parse("2026-03-04T22:00:00Z")); // 23:00 CET, 1h to midnight
        DailyTokenLimiter limiter = new DailyTokenLimiter(1000, clock);

        Reservation reservation = limiter.tryReserve(1000).orElseThrow();
        limiter.correct(reservation, 1000);

        long wait = limiter.millisUntilAvailable(1);

        assertTrue(wait > 0 && wait <= 3_600_000, "expected roughly <=1h until midnight, got " + wait);
    }

    /** A {@link Clock} fixed to Europe/Berlin the test can jump forward to exercise the midnight reset. */
    private static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock() {
            this(Instant.parse("2026-03-04T10:00:00Z"));
        }

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("Europe/Berlin");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        void set(Instant instant) {
            this.instant = instant;
        }
    }
}
