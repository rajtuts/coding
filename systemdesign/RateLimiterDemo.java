import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Rate Limiter - Low Level Design
 * Requirement: allow at most 5 requests per user per minute.
 *
 * Components
 *  - Clock                 : abstraction over time (testable)
 *  - RateLimitConfig       : maxRequests + window
 *  - RateLimitResult       : allowed / remaining / retryAfter
 *  - RateLimiter           : strategy interface
 *  - SlidingWindowLogRateLimiter (default, exact)
 *  - TokenBucketRateLimiter      (alternative, allows bursts, O(1) memory)
 *  - RateLimiterFactory    : picks algorithm
 *  - ApiGateway            : client of the limiter (e.g. filter / interceptor)
 *
 * Run: javac RateLimiterDemo.java && java RateLimiterDemo   (Java 16+)
 */
public class RateLimiterDemo {

    // ---------------------------------------------------------------- Clock
    interface Clock {
        long nowMillis();
    }

    static class SystemClock implements Clock {
        @Override public long nowMillis() { return System.currentTimeMillis(); }
    }

    /** Manually advanced clock for deterministic tests / demo. */
    static class FakeClock implements Clock {
        private final AtomicLong now = new AtomicLong(0);
        @Override public long nowMillis() { return now.get(); }
        void advance(Duration d) { now.addAndGet(d.toMillis()); }
    }

    // -------------------------------------------------------- Value objects
    record RateLimitConfig(int maxRequests, Duration window) {
        RateLimitConfig {
            if (maxRequests <= 0) throw new IllegalArgumentException("maxRequests must be > 0");
            if (window == null || window.isZero() || window.isNegative())
                throw new IllegalArgumentException("window must be positive");
        }
    }

    record RateLimitResult(boolean allowed, int remaining, long retryAfterMillis) {
        static RateLimitResult allowed(int remaining) { return new RateLimitResult(true, remaining, 0); }
        static RateLimitResult rejected(long retryAfterMillis) { return new RateLimitResult(false, 0, retryAfterMillis); }
    }

    // ------------------------------------------------------------- Strategy
    interface RateLimiter {
        RateLimitResult tryAcquire(String userId);
    }

    // ------------------------------------------- 1) Sliding Window Log (exact)
    /**
     * Stores the timestamp of each accepted request per user.
     * Accurate (no boundary burst problem). Memory: O(maxRequests) per user.
     * Atomicity per user is guaranteed by ConcurrentHashMap.compute (locks that key's bin only).
     */
    static class SlidingWindowLogRateLimiter implements RateLimiter {
        private final RateLimitConfig config;
        private final Clock clock;
        private final ConcurrentHashMap<String, Deque<Long>> logs = new ConcurrentHashMap<>();
        private final ScheduledExecutorService cleaner;

        SlidingWindowLogRateLimiter(RateLimitConfig config, Clock clock) {
            this(config, clock, true);
        }

        SlidingWindowLogRateLimiter(RateLimitConfig config, Clock clock, boolean startCleaner) {
            this.config = config;
            this.clock = clock;
            if (startCleaner) {
                cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "rate-limiter-cleaner");
                    t.setDaemon(true);
                    return t;
                });
                long period = config.window().toMillis();
                cleaner.scheduleAtFixedRate(this::evictIdleUsers, period, period, TimeUnit.MILLISECONDS);
            } else {
                cleaner = null;
            }
        }

        @Override
        public RateLimitResult tryAcquire(String userId) {
            long now = clock.nowMillis();
            long windowMs = config.window().toMillis();
            RateLimitResult[] out = new RateLimitResult[1];

            logs.compute(userId, (id, q) -> {
                if (q == null) q = new ArrayDeque<>();
                // drop timestamps that fell out of the window
                while (!q.isEmpty() && q.peekFirst() <= now - windowMs) q.pollFirst();

                if (q.size() < config.maxRequests()) {
                    q.addLast(now);
                    out[0] = RateLimitResult.allowed(config.maxRequests() - q.size());
                } else {
                    long retryAfter = q.peekFirst() + windowMs - now; // when oldest expires
                    out[0] = RateLimitResult.rejected(retryAfter);
                }
                return q;
            });
            return out[0];
        }

        /** Removes users with no activity in the current window (prevents memory leak). */
        void evictIdleUsers() {
            long now = clock.nowMillis();
            long windowMs = config.window().toMillis();
            for (String user : logs.keySet()) {
                logs.computeIfPresent(user, (id, q) -> {
                    while (!q.isEmpty() && q.peekFirst() <= now - windowMs) q.pollFirst();
                    return q.isEmpty() ? null : q; // null => remove entry atomically
                });
            }
        }

        void shutdown() { if (cleaner != null) cleaner.shutdownNow(); }
    }

    // ------------------------------------------------ 2) Token Bucket (burst)
    /**
     * Bucket capacity = maxRequests, refills continuously at maxRequests/window.
     * O(1) memory per user. Allows short bursts up to capacity.
     */
    static class TokenBucketRateLimiter implements RateLimiter {
        private static final class Bucket {
            double tokens;
            long lastRefillMs;
            Bucket(double tokens, long lastRefillMs) { this.tokens = tokens; this.lastRefillMs = lastRefillMs; }
        }

        private final RateLimitConfig config;
        private final Clock clock;
        private final double refillPerMs;
        private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

        TokenBucketRateLimiter(RateLimitConfig config, Clock clock) {
            this.config = config;
            this.clock = clock;
            this.refillPerMs = (double) config.maxRequests() / config.window().toMillis();
        }

        @Override
        public RateLimitResult tryAcquire(String userId) {
            long now = clock.nowMillis();
            RateLimitResult[] out = new RateLimitResult[1];

            buckets.compute(userId, (id, b) -> {
                if (b == null) b = new Bucket(config.maxRequests(), now);
                double refill = (now - b.lastRefillMs) * refillPerMs;
                b.tokens = Math.min(config.maxRequests(), b.tokens + refill);
                b.lastRefillMs = now;

                if (b.tokens >= 1) {
                    b.tokens -= 1;
                    out[0] = RateLimitResult.allowed((int) b.tokens);
                } else {
                    long retryAfter = (long) Math.ceil((1 - b.tokens) / refillPerMs);
                    out[0] = RateLimitResult.rejected(retryAfter);
                }
                return b;
            });
            return out[0];
        }
    }

    // -------------------------------------------------------------- Factory
    enum Algorithm { SLIDING_WINDOW_LOG, TOKEN_BUCKET }

    static class RateLimiterFactory {
        static RateLimiter create(Algorithm algo, RateLimitConfig cfg, Clock clock) {
            return switch (algo) {
                case SLIDING_WINDOW_LOG -> new SlidingWindowLogRateLimiter(cfg, clock);
                case TOKEN_BUCKET -> new TokenBucketRateLimiter(cfg, clock);
            };
        }
    }

    // -------------------------------------------- Client (filter / interceptor)
    static class ApiGateway {
        private final RateLimiter limiter;

        ApiGateway(RateLimiter limiter) { this.limiter = limiter; }

        /** Mimics HTTP: 200 OK or 429 Too Many Requests (+ Retry-After). */
        String handle(String userId, String request) {
            RateLimitResult r = limiter.tryAcquire(userId);
            if (!r.allowed()) {
                return "429 Too Many Requests | user=" + userId
                        + " | Retry-After=" + (r.retryAfterMillis() / 1000.0) + "s";
            }
            return "200 OK | user=" + userId + " | " + request + " | remaining=" + r.remaining();
        }
    }

    // ----------------------------------------------------------------- Demo
    public static void main(String[] args) {
        FakeClock clock = new FakeClock();
        RateLimitConfig cfg = new RateLimitConfig(5, Duration.ofMinutes(1)); // 5 req / user / min

        RateLimiter limiter = new SlidingWindowLogRateLimiter(cfg, clock, false);
        ApiGateway gateway = new ApiGateway(limiter);

        System.out.println("=== user1: 7 rapid requests ===");
        for (int i = 1; i <= 7; i++) System.out.println(i + ". " + gateway.handle("user1", "GET /orders"));

        System.out.println("\n=== user2 is unaffected (per-user limit) ===");
        System.out.println(gateway.handle("user2", "GET /orders"));

        System.out.println("\n=== after 61 seconds, user1 is allowed again ===");
        clock.advance(Duration.ofSeconds(61));
        System.out.println(gateway.handle("user1", "GET /orders"));

        System.out.println("\n=== Token bucket variant ===");
        FakeClock clock2 = new FakeClock();
        ApiGateway g2 = new ApiGateway(RateLimiterFactory.create(Algorithm.TOKEN_BUCKET, cfg, clock2));
        for (int i = 1; i <= 6; i++) System.out.println(i + ". " + g2.handle("user1", "POST /pay"));
        clock2.advance(Duration.ofSeconds(12)); // refill rate = 1 token / 12s
        System.out.println("after 12s: " + g2.handle("user1", "POST /pay"));
    }
}
