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
    void admitsAnOversizedSingleRequestWhenNothingIsCommittedYetToday() {
        DailyTokenLimiter limiter = new DailyTokenLimiter(1000, new MutableClock());

        assertTrue(limiter.tryReserve(5000).isPresent(),
                "an oversized request must be admitted when today's committed usage is still zero");
    }

    @Test
    void oversizedRequestIsRejectedOnceSomethingIsAlreadyCommittedToday() {
        DailyTokenLimiter limiter = new DailyTokenLimiter(1000, new MutableClock());

        Reservation reservation = limiter.tryReserve(200).orElseThrow();
        limiter.correct(reservation, 200, 0, 200, 0, 0); // something already committed today

        assertFalse(limiter.tryReserve(5000).isPresent(),
                "an oversized request must not jump the queue once other usage is already committed");
    }

    @Test
    void usageOnlyEverIncreasesAndNeverDropsBackDownAfterCorrection() {
        DailyTokenLimiter limiter = new DailyTokenLimiter(1000, new MutableClock());

        Reservation first = limiter.tryReserve(800).orElseThrow(); // worst-case max_tokens estimate
        limiter.correct(first, 50, 0, 50, 0, 0); // actual usage turned out much lower than the reservation
        assertEquals(50, limiter.snapshot().usage());

        Reservation second = limiter.tryReserve(700).orElseThrow();
        limiter.correct(second, 30, 0, 30, 0, 0);
        assertEquals(80, limiter.snapshot().usage(), "usage must accumulate real usage only, never decrease");
    }

    @Test
    void snapshotAccumulatesTheCostRelevantBreakdownAcrossRequests() {
        DailyTokenLimiter limiter = new DailyTokenLimiter(10_000, new MutableClock());

        Reservation first = limiter.tryReserve(500).orElseThrow();
        limiter.correct(first, 100, 20, 60, 15, 25); // input=100 (fresh=60, cacheCreate=15, cacheRead=25), output=20

        Reservation second = limiter.tryReserve(500).orElseThrow();
        limiter.correct(second, 40, 10, 10, 0, 30); // input=40 (fresh=10, cacheCreate=0, cacheRead=30), output=10

        DailyTokenLimiter.Snapshot snapshot = limiter.snapshot();
        assertEquals(140, snapshot.inputTokens(), "100 + 40");
        assertEquals(30, snapshot.outputTokens(), "20 + 10");
        assertEquals(70, snapshot.freshInputTokens(), "60 + 10");
        assertEquals(15, snapshot.cacheCreationTokens(), "15 + 0");
        assertEquals(55, snapshot.cacheReadTokens(), "25 + 30");
        assertEquals(170, snapshot.usage(), "140 input + 30 output");
    }

    @Test
    void breakdownResetsToZeroAtLocalMidnightAlongsideUsage() {
        MutableClock clock = new MutableClock(Instant.parse("2026-03-04T22:59:00Z")); // 23:59 CET
        DailyTokenLimiter limiter = new DailyTokenLimiter(10_000, clock);

        Reservation reservation = limiter.tryReserve(500).orElseThrow();
        limiter.correct(reservation, 100, 20, 60, 15, 25);
        assertEquals(120, limiter.snapshot().usage(), "100 input + 20 output");

        clock.set(Instant.parse("2026-03-04T23:05:00Z")); // 00:05 CET next day

        DailyTokenLimiter.Snapshot snapshot = limiter.snapshot();
        assertEquals(0, snapshot.usage());
        assertEquals(0, snapshot.inputTokens());
        assertEquals(0, snapshot.outputTokens());
        assertEquals(0, snapshot.freshInputTokens());
        assertEquals(0, snapshot.cacheCreationTokens());
        assertEquals(0, snapshot.cacheReadTokens());
    }

    @Test
    void rejectsWhenCommittedUsagePlusWorstCaseWouldExceedBudget() {
        DailyTokenLimiter limiter = new DailyTokenLimiter(1000, new MutableClock());

        Reservation reservation = limiter.tryReserve(900).orElseThrow();
        limiter.correct(reservation, 900, 0, 900, 0, 0); // commit the full worst case as real usage

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
        limiter.correct(reservation, 1000, 0, 1000, 0, 0);

        assertFalse(limiter.tryReserve(1).isPresent(), "still the same calendar day, budget should stay exhausted");
    }

    @Test
    void resetsAtLocalMidnightRegardlessOfWhenTheLastReservationWasMade() {
        MutableClock clock = new MutableClock(Instant.parse("2026-03-04T22:00:00Z")); // 23:00 CET
        DailyTokenLimiter limiter = new DailyTokenLimiter(1000, clock);

        Reservation reservation = limiter.tryReserve(1000).orElseThrow();
        limiter.correct(reservation, 1000, 0, 1000, 0, 0);
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
        limiter.correct(today, 200, 0, 200, 0, 0);

        limiter.correct(yesterday, 999, 0, 999, 0, 0); // late correction for a reservation from the previous day
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
        limiter.correct(reservation, 1000, 0, 1000, 0, 0);

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
