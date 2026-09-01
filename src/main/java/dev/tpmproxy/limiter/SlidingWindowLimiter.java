package dev.tpmproxy.limiter;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Generic rolling-window token budget (SPEC.md Section 5.2/5.4). Used both
 * for the 60s TPM budget and the 24h daily budget - the window length is a
 * constructor parameter, not hardcoded. Not fixed buckets - expired entries
 * are evicted lazily on every access. The limit itself is mutable at runtime.
 */
public final class SlidingWindowLimiter {

    private static final long POLL_INTERVAL_MILLIS = 250L;

    private final Deque<Reservation> window = new ArrayDeque<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final Clock clock;
    private final long windowMillis;
    private volatile int limit;
    private long currentSum = 0;

    public SlidingWindowLimiter(int initialLimit, long windowMillis) {
        this(initialLimit, windowMillis, Clock.systemUTC());
    }

    SlidingWindowLimiter(int initialLimit, long windowMillis, Clock clock) {
        this.limit = initialLimit;
        this.windowMillis = windowMillis;
        this.clock = clock;
    }

    public int limit() {
        return limit;
    }

    public void setLimit(int newLimit) {
        this.limit = newLimit;
    }

    /** Reserves {@code tokens} immediately if the budget allows it. */
    public Optional<Reservation> tryReserve(int tokens) {
        lock.lock();
        try {
            evictExpired();
            if (currentSum + tokens > limit) {
                return Optional.empty();
            }
            Reservation reservation = new Reservation(clock.millis(), tokens);
            window.addLast(reservation);
            currentSum += tokens;
            return Optional.of(reservation);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Polls for available budget until it can reserve {@code tokens} or
     * {@code timeoutMillis} elapses (SPEC.md Section 5.3).
     */
    public Optional<Reservation> reserveBlocking(int tokens, long timeoutMillis) throws InterruptedException {
        long deadline = clock.millis() + timeoutMillis;
        while (true) {
            Optional<Reservation> reservation = tryReserve(tokens);
            if (reservation.isPresent()) {
                return reservation;
            }
            long remaining = deadline - clock.millis();
            if (remaining <= 0) {
                return Optional.empty();
            }
            Thread.sleep(Math.min(POLL_INTERVAL_MILLIS, remaining));
        }
    }

    /** Replaces a reservation's provisional token count with the actual usage. */
    public void correct(Reservation reservation, int actualTokens) {
        lock.lock();
        try {
            evictExpired();
            if (window.contains(reservation)) {
                currentSum += (long) actualTokens - reservation.tokens;
                reservation.tokens = actualTokens;
            }
            // Already evicted (>window old): leave it - it aged out on its own.
        } finally {
            lock.unlock();
        }
    }

    /** Drops a reservation's cost entirely, e.g. when the upstream call failed before producing usage. */
    public void release(Reservation reservation) {
        correct(reservation, 0);
    }

    /** Milliseconds until enough budget is expected to free up for {@code tokens}, or 0 if available now. */
    public long millisUntilAvailable(int tokens) {
        lock.lock();
        try {
            evictExpired();
            long available = limit - currentSum;
            if (available >= tokens) {
                return 0;
            }
            long deficit = tokens - available;
            long freed = 0;
            for (Reservation r : window) {
                freed += r.tokens;
                if (freed >= deficit) {
                    return Math.max(0, (r.timestamp + windowMillis) - clock.millis());
                }
            }
            return windowMillis;
        } finally {
            lock.unlock();
        }
    }

    public Snapshot snapshot() {
        lock.lock();
        try {
            evictExpired();
            return new Snapshot(limit, currentSum, Math.max(0, limit - currentSum), window.size());
        } finally {
            lock.unlock();
        }
    }

    private void evictExpired() {
        long cutoff = clock.millis() - windowMillis;
        while (!window.isEmpty() && window.peekFirst().timestamp < cutoff) {
            Reservation expired = window.pollFirst();
            currentSum -= expired.tokens;
        }
    }

    public static final class Reservation {
        final long timestamp;
        volatile int tokens;

        private Reservation(long timestamp, int tokens) {
            this.timestamp = timestamp;
            this.tokens = tokens;
        }

        public int tokens() {
            return tokens;
        }
    }

    public record Snapshot(int limit, long windowUsage, long remaining, int activeReservations) {
    }
}
