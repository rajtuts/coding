# Rate Limiter: Low-Level Design

I kept the earlier example (5 requests per user per minute) as the concrete case, but the design is general.

## 1. Requirements

**Functional**
- Allow at most N requests per user within a time window (here N=5, window=1 min).
- Return allowed or rejected, plus the remaining quota and a retry-after time.
- Make limits configurable per user, tier or API.

**Non-functional**
- Low latency (O(1) per check) and thread-safe.
- No unbounded memory growth.
- Pluggable algorithms.
- Testable time.
- Extendable to a distributed setup.

## 2. Class Design

```
            +------------------+
            |  <<interface>>   |
            |   RateLimiter    |
            | tryAcquire(key)  |--> RateLimitResult(allowed, remaining, retryAfterMs)
            +--------^---------+
                     |
     +---------------+----------------+-----------------+
     |                                |                 |
SlidingWindowLog            TokenBucket          (FixedWindow / RedisLimiter ...)
 - Map<user, Deque<ts>>      - Map<user, Bucket>
 - RateLimitConfig           - RateLimitConfig
 - Clock                     - Clock

RateLimitConfig(maxRequests, window)     Clock <<interface>> : SystemClock, FakeClock
RateLimiterFactory.create(Algorithm, cfg, clock)
ApiGateway / Filter  --uses-->  RateLimiter
```

**Patterns used**
- **Strategy:** swap algorithms behind `RateLimiter`.
- **Factory:** choose the algorithm from configuration.
- **Dependency injection:** `Clock` is injected so the window can be tested without waiting.

## 3. Algorithm Comparison

| Algorithm | Accuracy | Memory per user | Burst behavior | Use when |
|---|---|---|---|---|
| Fixed window counter | Low (2x burst at window edge) | O(1) | Bursty at boundaries | Simple, approximate limits |
| **Sliding window log** | Exact | O(N) | Strict | Small N, accuracy matters (my default) |
| Sliding window counter | Approximate (weighted) | O(1) | Smooth | Large N, high scale |
| Token bucket | Good | O(1) | Allows bursts up to capacity | APIs that tolerate short bursts |
| Leaky bucket | Good | O(queue) | Smooths output rate | Traffic shaping |

## 4. Request Flow (sliding window log)

1. The gateway extracts the key (userId, API key or IP).
2. `tryAcquire(key)` reads `now` from the `Clock`.
3. It drops timestamps at or before `now - window`.
4. If fewer than 5 timestamps remain, it appends `now` and returns `allowed(remaining = 5 - size)`.
5. Otherwise it returns `rejected(retryAfter = oldest + window - now)`.
6. The gateway returns **200**, or **429** with `Retry-After`, `X-RateLimit-Limit` and `X-RateLimit-Remaining` headers.

## 5. Concurrency

- Each user's check-and-update runs inside `ConcurrentHashMap.compute`. That is atomic per key, so one user can't exceed the limit under parallel requests, and different users don't block each other.
- A background task evicts idle users with `computeIfPresent`, returning `null` to remove the entry atomically.
- Avoid check-then-act on a plain `get()` followed by `put()`, which has a race condition.

## 6. Extensibility

- **Per-tier or per-API limits:** introduce a `RateLimitPolicyResolver` that maps (user, endpoint) to a `RateLimitConfig`, and keep one limiter instance per policy. Use a composite key such as `userId:endpoint`.
- **Multiple rules together:** a `CompositeRateLimiter` that applies several limits (for example 5/min and 100/day) and rejects if any fails.
- **Different key types:** the interface takes a `String key`, so it works for user, IP or API key without change.

## 7. Distributed Version

An in-memory map limits only per instance. For multiple servers:
- Keep the same `RateLimiter` interface and add a `RedisSlidingWindowRateLimiter`.
- Run `ZREMRANGEBYSCORE` (evict), `ZCARD` (count) and `ZADD` (record) in **one Lua script** so the check is atomic.
- Set `EXPIRE` on each key to the window length for automatic cleanup.
- Decide a failure policy for when Redis is down: **fail-open** (availability) or **fail-closed** (protection).
- Use Redis server time or a single clock source to avoid skew between app servers.

## 8. Edge Cases

- Requests at exactly the window boundary: the code uses `<= now - window` so expired entries are dropped consistently.
- Rejected requests are not logged, so a blocked user isn't penalised further.
- Invalid config (N ≤ 0 or a zero window) fails fast in the `RateLimitConfig` constructor.
- Clock going backwards: use a monotonic clock (`System.nanoTime`) when you don't need wall-clock alignment.

## 9. Test Plan

- 5 requests are allowed and the 6th is rejected.
- After advancing the fake clock by 61 seconds, requests are allowed again.
- Two users don't affect each other.
- Many threads hitting one user allow exactly 5 requests.
- `retryAfter` equals the time until the oldest entry expires.
- The cleaner removes idle users.

The full Java implementation (sliding window log, token bucket, factory, gateway and demo) is in `RateLimiterDemo.java` from my previous message. I can add the Redis/Lua version, the multi-rule composite limiter, or JUnit tests next if you'd like.
