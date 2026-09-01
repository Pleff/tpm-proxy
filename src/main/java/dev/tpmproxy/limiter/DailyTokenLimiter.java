package dev.tpmproxy.limiter;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Calendar-day token budget (SPEC.md Section 5.5): counts tokens between
 * local midnight and the next local midnight, not a rolling 24h window.
 * On a day change the running total resets to zero in one step, instead of
 * individual reservations aging out one at a time like the TPM limiter.
 */
public final class DailyTokenLimiter {

    private final ReentrantLock lock = new ReentrantLock();
    private final Clock clock;
    private final ZoneId zone;
    private volatile int limit;
    private LocalDate currentDay;
    private long currentUsage = 0;

    public DailyTokenLimiter(int initialLimit) {
        this(initialLimit, Clock.systemDefaultZone());
    }

    DailyTokenLimiter(int initialLimit, Clock clock) {
        this.limit = initialLimit;
        this.clock = clock;
        this.zone = clock.getZone();
        this.currentDay = LocalDate.now(clock);
    }

    public int limit() {
        return limit;
    }

    public void setLimit(int newLimit) {
        this.limit = newLimit;
    }

    /** Reserves {@code tokens} immediately if today's budget allows it. */
    public Optional<Reservation> tryReserve(int tokens) {
        lock.lock();
        try {
            rolloverIfNewDay();
            if (currentUsage + tokens > limit) {
                return Optional.empty();
            }
            Reservation reservation = new Reservation(currentDay, tokens);
            currentUsage += tokens;
            return Optional.of(reservation);
        } finally {
            lock.unlock();
        }
    }

    /** Replaces a reservation's provisional token count with the actual usage. */
    public void correct(Reservation reservation, int actualTokens) {
        lock.lock();
        try {
            rolloverIfNewDay();
            if (reservation.day.equals(currentDay)) {
                currentUsage += (long) actualTokens - reservation.tokens;
                reservation.tokens = actualTokens;
            }
            // Otherwise the reservation's day already rolled over - it reset to zero on its own.
        } finally {
            lock.unlock();
        }
    }

    /** Drops a reservation's cost entirely, e.g. when the upstream call failed before producing usage. */
    public void release(Reservation reservation) {
        correct(reservation, 0);
    }

    /** Milliseconds until local midnight, when the daily budget resets - or 0 if available now. */
    public long millisUntilAvailable(int tokens) {
        lock.lock();
        try {
            rolloverIfNewDay();
            if (limit - currentUsage >= tokens) {
                return 0;
            }
            ZonedDateTime now = ZonedDateTime.now(clock);
            ZonedDateTime nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay(zone);
            return Duration.between(now, nextMidnight).toMillis();
        } finally {
            lock.unlock();
        }
    }

    public Snapshot snapshot() {
        lock.lock();
        try {
            rolloverIfNewDay();
            return new Snapshot(limit, currentUsage, Math.max(0, limit - currentUsage), currentDay);
        } finally {
            lock.unlock();
        }
    }

    private void rolloverIfNewDay() {
        LocalDate today = LocalDate.now(clock);
        if (!today.equals(currentDay)) {
            currentDay = today;
            currentUsage = 0;
        }
    }

    public static final class Reservation {
        private final LocalDate day;
        private volatile int tokens;

        private Reservation(LocalDate day, int tokens) {
            this.day = day;
            this.tokens = tokens;
        }

        public int tokens() {
            return tokens;
        }
    }

    public record Snapshot(int limit, long usage, long remaining, LocalDate day) {
    }
}
