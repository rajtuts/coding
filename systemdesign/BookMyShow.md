# BookMyShow — Ticket Booking System: High-Level Design (HLD)

We’ll design a ticket booking platform similar to BookMyShow, where users discover movies and events, select a show and seats, temporarily reserve seats, pay, and receive confirmed tickets.

The most important system design challenge is preventing two users from booking the same seat, especially when popular movie tickets go on sale and thousands of users try to book simultaneously.

## 1. Requirements clarification

### Functional requirements

- Browse movies, events, cinemas, venues, and showtimes.
- View seat availability for a particular show.
- Select one or more seats.
- Temporarily lock seats while completing payment.
- Process payments and confirm bookings.
- Generate tickets with booking IDs and QR codes.
- Cancel bookings and process eligible refunds.
- Notify users about booking confirmations and payment failures.

### Non-functional requirements

| Requirement        | Design objective                                                     |
| ------------------ | -------------------------------------------------------------------- |
| Strong consistency | A seat cannot be sold to two customers                               |
| High availability  | Browsing and show discovery remain available during partial failures |
| Scalability        | Support traffic spikes during popular ticket releases                |
| Low latency        | Fast search, seat-map loading, and booking responses                 |
| Durability         | Confirmed bookings and payment records must be preserved             |
| Fault tolerance    | Recover safely from payment timeouts and service failures            |
| Security           | Protect accounts, payments, and ticket QR codes                      |

Out of scope initially: dynamic seat pricing, loyalty programs, recommendations, resale marketplaces, and complex group-booking optimization.

## 2. High-level architecture

Web App / Mobile App

Movie discovery · Showtimes · Seat selection · Checkout

CDN + API Gateway

Static content · Authentication · Routing · Rate limiting

Catalog Service

Movies, cinemas, shows, search

Inventory Service

Seat availability and temporary holds

Booking Service

Booking lifecycle and confirmation

Payment Service

Payment authorization and reconciliation

Event Bus — Kafka or equivalent

Booking events · Notifications · Analytics · Recovery workflows

Relational Database

Seats · Holds · Bookings · Payments

Redis / Cache

Catalog cache · Temporary state · Availability hints

Conceptual architecture. The inventory database is authoritative for seat ownership; cached availability is not sufficient to confirm a booking.

## 3. Core services and responsibilities

| Service              | Responsibility                                          |
| -------------------- | ------------------------------------------------------- |
| API Gateway          | Authentication, routing, rate limiting                  |
| Catalog Service      | Movies, cinemas, venues, showtimes, search              |
| Seat Map Service     | Layouts, seat categories, seat-map display              |
| Inventory Service    | Availability, atomic seat holds, hold expiry            |
| Booking Service      | Booking creation, state transitions, confirmation       |
| Payment Service      | Payment authorization, capture, refunds, reconciliation |
| Pricing Service      | Ticket prices, taxes, fees, discounts                   |
| Notification Service | Booking confirmations, email, SMS, push                 |
| Ticket Service       | QR-code generation and ticket validation                |
| Admin Service        | Cinema configuration, show scheduling, seat allocation  |

For the initial implementation, these services can be logical modules in a modular monolith. The inventory and booking boundaries should be explicit because they contain the most critical consistency rules.

## 4. Core data model

Movie

movie_id, title, language, duration

Show

show_id, movie_id, screen_id, start_time

ShowSeat

show_id, seat_id, price, status, hold_id, expires_at

Booking

booking_id, user_id, show_id, status, total_amount

Payment / Ticket

payment_id, booking_id, payment_status / ticket_id, QR token

A ShowSeat represents a particular physical seat for a particular show. Seat inventory is therefore separate for every show.

Important database constraints:

- `ShowSeat`: unique key on `(show_id, seat_id)`.
- `BookingSeat`: unique key on `(booking_id, seat_id)` or an equivalent show-scoped booking-seat relationship.
- `Payment`: unique payment-provider transaction reference, where supported.
- Idempotency keys for booking and payment creation.
- A database-enforced rule that prevents multiple active allocations for the same show and seat.

A physical seat such as `A10` can be sold for the 6 PM show and again for the 9 PM show. The inventory key must include the show.

## 5. Seat inventory and booking states

Seat inventory needs its own lifecycle.

AVAILABLE

Seat can be selected

HELD

Temporarily reserved for one checkout

BOOKED

Booking is confirmed

A failed or expired hold returns to AVAILABLE, provided no valid booking has claimed the seat.

The booking itself follows a different lifecycle:

`PENDING → PAYMENT_PENDING → CONFIRMED`

Failure branches include `PAYMENT_FAILED`, `EXPIRED`, and `CANCELLED`. Refunds should be tracked explicitly rather than treating every cancellation as an immediate successful refund.

Important distinction: a seat hold is not a confirmed ticket. Only the successful booking and payment workflow can produce a confirmed booking, subject to the platform's payment policy.

## 6. End-to-end booking flow

Step 1 — Discover a show

The user searches movies, cinemas, or events. The Catalog Service returns showtimes and cached seat-map information. Search traffic can be served from read replicas and caches.

Step 2 — Select seats and create a hold

The client submits the show ID and selected seat IDs. The Inventory Service atomically claims all requested seats only if every seat is available. A hold expires after a short interval, such as five minutes.

Step 3 — Create a pending booking and initiate payment

The Booking Service creates a pending booking linked to the hold. The Payment Service requests payment authorization or payment initiation from the payment provider, using an idempotency key.

Step 4 — Verify payment and confirm seats

After verified payment success, the system checks that the hold remains valid and atomically commits the seat allocation and booking confirmation. If the payment result is ambiguous, reconcile with the payment provider before retrying or releasing inventory.

Step 5 — Issue the ticket

The Ticket Service generates a unique ticket ID and QR token. An event triggers the Notification Service to deliver the confirmation and ticket to the user.

## 7. How do we prevent double booking?

This is the most important part of the design.

Imagine two users, Alice and Bob, both select seat `A10` for show `SHOW101` at almost exactly the same time.

A naive implementation might do this:

1. Check whether the seat is available.
2. If available, update it to held.

Both requests could read `AVAILABLE` before either update occurs. Both users might then believe they own the seat.

### Correct approach: atomic conditional update

Use a transactional database as the source of truth. For example, an atomic conditional update:

```
UPDATE show_seats
SET status = 'HELD',
    hold_id = :hold_id,
    expires_at = :expires_at
WHERE show_id = :show_id
  AND seat_id = :seat_id
  AND status = 'AVAILABLE';
```

The application must check the affected row count:

- `1` row updated: the seat was successfully claimed.
- `0` rows updated: the seat is no longer available.

For multiple seats, claim all requested seats in one transaction. If any seat cannot be claimed, roll back the transaction so the user does not end up with only part of the requested selection.

Concurrency example

Alice

Requests seat A10

Claim succeeds

Bob

Requests seat A10

Claim fails

The database serializes conflicting updates so only one request can claim the currently available seat.

### What about Redis locks?

Redis can be used to coordinate short-lived reservations and reduce contention, but a distributed lock alone should not be the only protection against double booking. Lock expiry, network failures, and stale lock holders can cause problems.

The final inventory change should still be protected by an authoritative database transaction or another storage mechanism that guarantees exclusive allocation.

## 8. Handling payment failures and timeouts

Payments introduce a distributed transaction problem: the booking database and an external payment provider cannot usually participate in the same ACID transaction.

Use a Saga-style workflow with explicit states and compensating actions.

| Scenario                           | Action                                                                   |
| ---------------------------------- | ------------------------------------------------------------------------ |
| Payment succeeds                   | Confirm the booking and seat allocation                                  |
| Payment is declined                | Mark payment failed and release the hold                                 |
| User abandons checkout             | Expire the hold and release seats                                        |
| Payment request times out          | Query the provider or await its verified webhook before deciding         |
| Payment succeeds after hold expiry | Reconcile the result and refund or recover inventory according to policy |
| Notification fails                 | Retry asynchronously without undoing the confirmed booking               |

Never assume that a timeout means a payment failed. The provider may have processed the payment even though the response was lost.

Use idempotent payment operations, verified webhooks, durable payment state, and a reconciliation process. Publish booking events through a transactional outbox or equivalent reliable mechanism so that a committed booking cannot silently lose its notification event.

## 9. API design

Representative APIs for the booking workflow:

| Method | Endpoint                           | Purpose                            |
| ------ | ---------------------------------- | ---------------------------------- |
| `GET`  | `/v1/movies`                       | Browse movies                      |
| `GET`  | `/v1/shows/{showId}/seats`         | Get seat map and availability      |
| `POST` | `/v1/shows/{showId}/holds`         | Temporarily reserve selected seats |
| `POST` | `/v1/bookings`                     | Create a pending booking           |
| `POST` | `/v1/bookings/{bookingId}/payment` | Initiate payment                   |
| `GET`  | `/v1/bookings/{bookingId}`         | Get booking status                 |
| `POST` | `/v1/bookings/{bookingId}/cancel`  | Request cancellation               |
| `GET`  | `/v1/bookings/{bookingId}/ticket`  | Retrieve confirmed ticket          |

Example seat-hold request:

```
POST /v1/shows/SHOW101/holds
Idempotency-Key: hold-request-123
Content-Type: application/json
```

```
{
  "seatIds": ["A10", "A11"]
}
```

Example response:

```
{
  "holdId": "HOLD5001",
  "showId": "SHOW101",
  "seatIds": ["A10", "A11"],
  "status": "HELD",
  "expiresAt": "2026-10-24T15:00:00Z"
}
```

The server determines the authoritative expiry time. A client must not be able to extend a hold simply by changing its local clock.

## 10. Storage and caching strategy

| Data                        | Storage                                         | Reason                                      |
| --------------------------- | ----------------------------------------------- | ------------------------------------------- |
| Movies, cinemas, venues     | SQL database + cache                            | Read-heavy catalog data                     |
| Showtimes                   | SQL database + cache                            | Frequent searches                           |
| Seat inventory              | Transactional SQL database                      | Strong consistency and exclusive allocation |
| Active hold metadata        | SQL database; optionally Redis for acceleration | Durable ownership and expiry                |
| Bookings                    | SQL database                                    | Transactional lifecycle and audit trail     |
| Payments                    | SQL database + provider references              | Reconciliation and financial correctness    |
| Ticket data                 | SQL database; QR token can be signed            | Durable tickets and validation              |
| Notifications and analytics | Event bus + downstream storage                  | Asynchronous processing                     |

Redis is excellent for cached availability, temporary coordination, and fast reads. However, stale cached availability must never be used to guarantee that a seat can be booked.

## 11. Scalability and high availability

| Challenge                           | Solution                                                           |
| ----------------------------------- | ------------------------------------------------------------------ |
| Huge traffic during ticket releases | Virtual waiting room, rate limiting, admission control             |
| Heavy movie searches                | CDN, caching, read replicas                                        |
| Hot shows and popular seats         | Partition inventory by show; control contention and queue requests |
| Large seat maps                     | Return compact seat data and use incremental availability updates  |
| Database contention                 | Short transactions, conditional updates, suitable indexes          |
| Payment provider outage             | Durable pending states, retries, reconciliation                    |
| Event delivery failures             | Transactional outbox, retry policy, dead-letter queue              |
| Regional failure                    | Multi-zone deployment and tested database recovery                 |
| Duplicate API calls                 | Idempotency keys and unique constraints                            |

For a popular show, inventory can be partitioned by `show_id` so unrelated shows do not compete for the same database resources. A single exceptionally popular show can still become a hot partition, so admission control and carefully designed concurrency handling remain important.

### Capacity estimation example

Suppose a large ticket release receives 100,000 booking attempts over 10 minutes.

\\[ \text{Average booking attempts/sec} =\frac{100{,}000}{600} \approx 167 \\]

If the release creates a burst of 10,000 attempts per second, the system must be designed for the burst rather than just the average. Browsing and seat-map reads can scale separately from transactional seat allocation.

## 12. Interactive progress tracker

Use this checklist to track your understanding of the design.

## Booking system readiness

# 0%

Requirements and architecture

Define functional requirements, non-functional goals, services, and boundaries.

Data model

Understand ShowSeat, Hold, Booking, Payment, and Ticket entities.

Seat concurrency

Explain atomic updates, transactions, and double-booking prevention.

Hold expiry

Handle abandoned checkouts and safely release seats.

Payment workflow

Explain idempotency, timeouts, webhooks, Saga compensation, and reconciliation.

API design

Design seat-map, hold, booking, payment, and cancellation APIs.

Scalability

Estimate load and discuss caching, hot shows, partitioning, and admission control.

Reliability and security

Discuss recovery, event delivery, QR validation, and authorization.

Start mock HLD interview

Final interview takeaway: The three most important topics are atomic seat reservation, temporary hold expiry, and reliable payment-to-booking confirmation. If you can explain how these work under concurrent requests and partial failures, you have the foundation of a strong BookMyShow HLD.

---

For BookMyShow, when a user selects two seats, the database should be the source of truth, and Redis should be used for fast reads and temporary coordination—not as a second independent source of truth.

The most important rule is: don't update Redis and the database independently and assume both succeeded. They can fail at different times.

Let's use your scenario: User A selects C12 and C13, then clicks Continue.

## 1. Recommended architecture

Frontend

Select C12, C13 → Continue

Spring Boot Booking Service

SQL database — authoritative

Atomically hold both seats in one transaction

After successful commit

Redis — fast seat availability

Update or invalidate cached seat data

## 2. Step-by-step: User A holds two seats

### Step 1: Update the database first

Start a database transaction and attempt to hold both seats using conditional updates.

```
BEGIN;

UPDATE show_seats
SET status = 'HELD',
    user_id = :userId,
    hold_id = :holdId,
    hold_expires_at = :expiry
WHERE show_id = :showId
  AND seat_id = 'C12'
  AND status = 'AVAILABLE';

-- Application must verify 1 row updated.

UPDATE show_seats
SET status = 'HELD',
    user_id = :userId,
    hold_id = :holdId,
    hold_expires_at = :expiry
WHERE show_id = :showId
  AND seat_id = 'C13'
  AND status = 'AVAILABLE';

-- Application must verify 1 row updated.

COMMIT;
```

If either update fails, roll back the entire transaction. Both seats must be held successfully or neither should be.

### Step 2: Update Redis only after the commit

Once the database transaction commits successfully, update or invalidate the corresponding Redis cache entries.

For example, if your cache stores each seat's status:

```
seat:show101:C12 → HELD
seat:show101:C13 → HELD
```

If your cache stores a full seat map, invalidate that cache entry instead so the next request reloads current state.

Why database first? If Redis is updated to `HELD` but the database transaction fails, users may see seats as unavailable even though they were never successfully held.

## 3. What if Redis fails after the database succeeds?

This is the important distributed-systems problem.

Suppose the SQL transaction succeeds, but Redis is unavailable.

- Database: C12 and C13 are `HELD`.
- Redis: may still show them as `AVAILABLE`.

If your booking service trusts Redis, another user might be shown stale availability. However, the database's conditional update rejects that user's attempt to hold those seats.

So the booking remains correct, even if the displayed seat map is temporarily stale.

To make Redis recover automatically, use a transactional outbox or a retry/reconciliation mechanism:

1. Update the seats and write an outbox event in the same SQL transaction.
2. A background worker reads the committed event.
3. The worker updates or invalidates the relevant Redis keys.
4. If Redis is unavailable, the worker retries until successful.

This avoids losing the cache-update notification when the application crashes after committing the database transaction.

## 4. What about releasing expired holds?

When the hold expires, follow the same principle:

1. Conditionally release the expired hold in the database, verifying its `hold_id`.
2. Commit the transaction.
3. Update or invalidate Redis through the outbox/retry mechanism.

Don't let Redis TTL alone determine whether a seat is available for booking. Redis can expire a key, but the database must still enforce whether the seat can be claimed.

## 5. Final design decision

| Operation         | Database                                             | Redis                                            |
| ----------------- | ---------------------------------------------------- | ------------------------------------------------ |
| Display seat map  | Usually not queried for every read if cache is fresh | Read cached seat availability                    |
| Hold C12 and C13  | Atomic transaction; authoritative decision           | Update/invalidate after commit                   |
| Payment succeeds  | Confirm booking transactionally                      | Refresh/invalidate cache                         |
| Hold expires      | Conditionally release the matching hold              | Refresh/invalidate cache                         |
| Redis unavailable | Continue enforcing booking correctness               | Retry cache update; tolerate temporary staleness |

Interview takeaway: SQL protects against double booking. Redis improves read performance. A transactional outbox or reliable retry mechanism keeps the cache synchronized eventually. Never rely on Redis availability alone to authorize a seat booking.
