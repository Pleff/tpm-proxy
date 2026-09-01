package de.bestblu.tools.tpmproxy.limiter;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Rolling 60s TPM budget (SPEC.md Section 5.2). Not fixed minute buckets -
 * expired entries are evicted lazily on every access. The limit itself is
 * mutable at runtime (Section 5.4, PUT /internal/limit). The daily budget
 * (Section 5.5) is a calendar-day counter instead - see DailyTokenLimiter,
 * not a reuse of this class.
 */
public final class SlidingWindowLimiter {

    private static final long WINDOW_MILLIS = 60_000L;
    private static final long POLL_INTERVAL_MILLIS = 250L;

    private final Deque<Reservation> window = new ArrayDeque<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final Clock clock;
    private final AtomicLong pendingTokens = new AtomicLong();
    private volatile int limit;
    private long currentSum = 0;

    public SlidingWindowLimiter(int initialLimit) {
        this(initialLimit, Clock.systemUTC());
    }

    SlidingWindowLimiter(int initialLimit, Clock clock) {
        this.limit = initialLimit;
        this.clock = clock;
    }

    public int limit() {
        return limit;
    }

    public void setLimit(int newLimit) {
        this.limit = newLimit;
    }

    /**
     * Reserves {@code tokens} immediately if the budget allows it.
     *
     * <p>A single request larger than the whole limit (e.g. a big conversation
     * history pushing input_tokens + max_tokens past TPM_LIMIT) can never
     * satisfy {@code currentSum + tokens <= limit} - not now, not ever, no
     * matter how long it waits. Since a single API call can't be split across
     * windows, such a request is admitted as soon as the window is completely
     * empty (nothing else in flight), even though it overshoots the limit by
     * itself. It then blocks everything else until it ages out 60s later -
     * the limit is enforced around it, not by rejecting it forever.
     */
    public Optional<Reservation> tryReserve(int tokens) {
        lock.lock();
        try {
            evictExpired();
            boolean fitsNormally = currentSum + tokens <= limit;
            boolean oversizedButWindowIsClear = tokens > limit && currentSum == 0;
            if (!fitsNormally && !oversizedButWindowIsClear) {
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
     * {@code timeoutMillis} elapses (SPEC.md Section 5.3). While waiting,
     * {@code tokens} counts toward {@link #pendingTokens()} - the backlog of
     * requests queued on this budget, processed as budget frees up.
     */
    public Optional<Reservation> reserveBlocking(int tokens, long timeoutMillis) throws InterruptedException {
        pendingTokens.addAndGet(tokens);
        try {
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
        } finally {
            pendingTokens.addAndGet(-tokens);
        }
    }

    /** Sum of tokens across requests currently waiting in {@link #reserveBlocking} for budget to free up. */
    public long pendingTokens() {
        return pendingTokens.get();
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
            // Already evicted (>60s old): leave it - it aged out on its own.
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
                    return Math.max(0, (r.timestamp + WINDOW_MILLIS) - clock.millis());
                }
            }
            return WINDOW_MILLIS;
        } finally {
            lock.unlock();
        }
    }

    public Snapshot snapshot() {
        lock.lock();
        try {
            evictExpired();
            return new Snapshot(limit, currentSum, Math.max(0, limit - currentSum), window.size(), pendingTokens.get());
        } finally {
            lock.unlock();
        }
    }

    private void evictExpired() {
        long cutoff = clock.millis() - WINDOW_MILLIS;
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

    public record Snapshot(int limit, long windowUsage, long remaining, int activeReservations, long pendingTokens) {
    }
}
