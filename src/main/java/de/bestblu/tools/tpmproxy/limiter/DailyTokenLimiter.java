package de.bestblu.tools.tpmproxy.limiter;

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
 *
 * Unlike the TPM limiter, this only ever counts <em>actual</em> usage - a
 * request's worst-case ({@code max_tokens}) estimate gates admission in
 * {@link #tryReserve} but is never added to the running total, so the
 * visible total is monotonically increasing (until the daily reset)
 * instead of spiking up on reservation and dropping back down once the
 * real usage is known.
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

    /**
     * Admits the request if today's committed usage plus this worst-case
     * estimate still fits the budget. Nothing is added to the running total
     * here - only {@link #correct} commits real usage.
     */
    public Optional<Reservation> tryReserve(int worstCaseTokens) {
        lock.lock();
        try {
            rolloverIfNewDay();
            if (currentUsage + worstCaseTokens > limit) {
                return Optional.empty();
            }
            return Optional.of(new Reservation(currentDay));
        } finally {
            lock.unlock();
        }
    }

    /**
     * Commits the actual usage to today's running total. A no-op if the
     * reservation's day has already rolled over - that day already reset
     * to zero on its own, so a late correction must not leak into today.
     */
    public void correct(Reservation reservation, int actualTokens) {
        lock.lock();
        try {
            rolloverIfNewDay();
            if (reservation.day.equals(currentDay)) {
                currentUsage += actualTokens;
            }
        } finally {
            lock.unlock();
        }
    }

    /** Records no usage for a reservation that never completed (e.g. the upstream call failed). */
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

        private Reservation(LocalDate day) {
            this.day = day;
        }
    }

    public record Snapshot(int limit, long usage, long remaining, LocalDate day) {
    }
}
