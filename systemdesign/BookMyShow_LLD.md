I'll build this the way we did ride sharing: the full design write-up, plus one runnable Java file with the self tests.

# Low-Level Design: BookMyShow (movie ticket booking)

The runnable implementation is in `BookMyShowApp.java`. I couldn't compile it because my sandbox has no JDK, so run `java BookMyShowApp.java test` once to confirm all 19 tests pass. `java BookMyShowApp.java` runs the demo, and Java 17+ is required.

## 1. Requirements clarification

**Functional (in scope)**
- Browse movies by city, find shows (by movie, date) and view a seat map with live availability
- Select 1 to 6 seats, hold them temporarily, pay, and receive a confirmation
- Cancel a booking with a tiered refund
- Admin: add theatres, screens and seat layouts, movies, and schedule shows
- Notifications on booking events

**Non-functional**
- **Correctness above all:** a seat is never sold twice, even under heavy contention (a popular opening-day show)
- **Fairness and UX:** a seat hold expires automatically (10 minutes), so abandoned carts don't block seats
- **Consistency:** booking state and seat state never diverge (for example, a customer never pays for a seat they don't get)
- **Extensibility:** new pricing rules, payment methods and cancellation policies need no core changes
- **Testability:** every dependency sits behind an interface, and time is injectable

**Out of scope:** food ordering, reviews, recommendations, real payment gateway integration, multi-currency, seat-map graphics, tax/GST rules, loyalty points

## 2. Actors and use cases

| Actor | Use cases |
|---|---|
| **Customer** | Search, view seat map, hold seats, pay, cancel, view history |
| **Admin / theatre owner** | Add theatre, screen and layout; add movie; schedule or cancel shows |
| **Payment gateway** (external) | Charge, refund |
| **Scheduler** (system) | Expire stale holds, release seats |
| **Notification provider** (external) | Email and SMS |

## 3. Core entities

| Entity | Key attributes |
|---|---|
| **City** | id, name |
| **Theatre** | id, name, city, screens |
| **Screen** | id, name, theatre, seats (fixed layout) |
| **Seat** | id ("A1"), row, number, type (SILVER/GOLD/PLATINUM) |
| **Movie** | id, title, duration, language, genre |
| **Show** | id, movie, screen, start/end time, base price, **ShowSeats** |
| **ShowSeat** | seat, status (AVAILABLE/LOCKED/BOOKED), holder, lock expiry |
| **Booking** | id, user, show, seat ids, amount, status, hold expiry, payment, refund |
| **Payment** | id, booking id, amount, method, status, txn id |
| **User** | id, name, email, phone |

**The key modeling insight is `Seat` vs `ShowSeat`.** A `Seat` is a physical chair on a screen and never changes. A `ShowSeat` is that chair *for one specific show*, and it carries the status. The same seat A1 can be booked for the 6 PM show and free for the 9 PM show. Putting a `status` on `Seat` is the classic mistake.

## 4. Enums and value objects

```java
enum SeatType { SILVER(1.0), GOLD(1.3), PLATINUM(1.6); final double multiplier; ... }
enum ShowSeatStatus { AVAILABLE, LOCKED, BOOKED }
enum BookingStatus  { PENDING, CONFIRMED, CANCELLED, EXPIRED }
enum PaymentStatus  { SUCCESS, FAILED, REFUNDED }

record Money(BigDecimal amount, String currency) { ... }   // BigDecimal, never double
record Seat(String id, String row, int number, SeatType type) { ... }
record Movie(String id, String title, int durationMinutes, String language, String genre) { ... }
record SeatInfo(String seatId, SeatType type, ShowSeatStatus status, Money price) { ... }
```

Records give immutability, `equals` and `hashCode` for free. Money validates non-negative amounts and rounds `HALF_UP` to 2 decimals.

## 5. Class diagram

```
 City ◄────── Theatre ◆──────► Screen ◆──────► Seat (immutable layout)
                                  │
                                  │ hosts
                                  ▼
 Movie ◄────────────────────── Show ◆─────────► ShowSeat ──► Seat
                                  ▲               status, holder, lockExpiry
                                  │               tryLock() confirm() releaseBy()
 User ◄──────── Booking ──────────┘
                 │  ◆──► Payment
                 │  status: BookingStatus (state machine)
                 │
 BookingService ◇──► SeatLockService        (hold / rollback)
       │        ◇──► PricingStrategy        ◄── BasePricing, WeekendPricing*, PromoPricing*
       │        ◇──► PaymentService ──► PaymentMethod ◄── Card, UPI, Wallet
       │        ◇──► CancellationPolicy     ◄── TieredRefundPolicy
       │        ◇──► BookingValidator
       │        ◇──► BookingRepository / CatalogRepository
       └────────◇──► List<BookingObserver>  ◄── Email, SMS, Analytics

 CatalogService   (admin: add theatre/movie, schedule show + overlap check)
 ShowQueryService (read side: search, seat map)           * = decorators
```

Legend: ◆ composition (lifecycle owned), ◇ injected dependency, ──► association.

## 6. Interfaces and abstractions

```java
interface PricingStrategy      { Money priceFor(Show show, Seat seat); }
interface PaymentMethod        { PaymentResult pay(User u, Money amt);
                                 default boolean refund(String txnId, Money amt) {...} }
interface CancellationPolicy   { Money refundFor(Booking b, Instant now); }   // throws if too late
interface BookingObserver      { void onEvent(BookingEvent e); }
interface CatalogRepository    { saveShow(...); findShow(...); showsOnScreen(...); ... }
interface BookingRepository    { Booking saveOrGetExisting(Booking b);        // atomic idempotency
                                 findLivePendingByUserAndShow(...); findExpiredPending(...); ... }
```

`Clock` is also an injected abstraction. That's what lets the tests move time forward 11 minutes instantly.

## 7. SOLID

| Principle | In this design |
|---|---|
| **S**ingle responsibility | `SeatLockService` only holds seats, `PaymentService` only charges, `CatalogService` is admin, `ShowQueryService` is read-only, and `BookingService` just orchestrates |
| **O**pen/closed | Festival pricing, a new wallet or a stricter refund policy are new classes. `BookingService` is untouched |
| **L**iskov | Any `PricingStrategy` (including decorators) is substitutable. Tests swap in a slow or flaky `PaymentMethod` lambda |
| **I**nterface segregation | `PaymentMethod` is 1 method plus a default. Observers have one callback. Read and write sides are separate services |
| **D**ependency inversion | Services receive interfaces through constructors (wired in `BookMyShowSystem`), never `new` a concrete strategy internally |

## 8. Design patterns

| Pattern | Where | Why |
|---|---|---|
| **Strategy** | Pricing, payment, cancellation policy | Rules change independently of the booking flow |
| **Decorator** | `WeekendPricing(PromoPricing(BasePricing))` | Stack price rules without a class per combination |
| **State** | `BookingStatus.canMoveTo()` | Legal transitions in one place |
| **Observer** | Email, SMS, analytics | The booking flow doesn't know who listens |
| **Factory** | `PaymentMethodFactory.of("UPI")` | Creates payment methods from request strings |
| **Builder** | `Booking.builder()`, `ScreenLayoutBuilder.row("A", 5, GOLD)` | Many fields / declarative layouts |
| **Repository** | `CatalogRepository`, `BookingRepository` | Swap in-memory for a DB |
| **Facade** | `BookMyShowSystem` | Single wiring point (composition root) |

## 9. State management

**Booking lifecycle**

```
                       confirm (paid)                 cancel (refund tier)
   PENDING ───────────────────────────► CONFIRMED ──────────────────────► CANCELLED
     │  │
     │  └── user cancels ───────────────────────────────────────────────► CANCELLED
     └───── hold TTL elapses (sweeper / lazy) ──────────────────────────► EXPIRED
```

**ShowSeat lifecycle**

```
   AVAILABLE ──tryLock──► LOCKED ──confirm──► BOOKED
       ▲                    │                    │
       └── expiry / release ┘                    │
       ▲                                         │
       └────────── cancel booking (releaseBy) ───┘
```

Booking state and seat state are updated together, in one service method. That coupling is the main source of bugs, which is why sections 13 and 14 exist.

## 10. Service / orchestrator layer

| Service | Responsibility |
|---|---|
| `CatalogService` | Add theatres and movies, schedule shows (rejects overlaps, including a 15-minute cleaning buffer) |
| `ShowQueryService` | `moviesInCity`, `searchShows`, `seatMap` |
| `SeatLockService` | All-or-nothing seat holds with rollback |
| `PaymentService` | Charge with retry, refund |
| `BookingValidator` | Business rules before any lock is taken |
| `BookingService` | `createBooking` → `confirmBooking` → `cancelBooking`, plus `expireStaleBookings` |

**The booking flow is deliberately two-step: hold, then pay.**

```java
Booking createBooking(req)  { idempotency check → validate → lockSeats (all-or-nothing)
                              → price → save → publish }
Booking confirmBooking(...) { authorize → verify holds still valid → charge
                              → confirm each seat → mark CONFIRMED → publish }
```

This is why the code locks first. Charging first and then discovering the seat is gone means issuing refunds. Locking first means payment only happens for seats you hold.

## 11. Repository layer

- Services depend only on the repository interfaces. The in-memory versions use `ConcurrentHashMap`. In production these become JPA/JDBC implementations.
- `saveOrGetExisting` makes the idempotency check **atomic** (`putIfAbsent`), so two concurrent double-taps can't create two bookings.
- Repositories return domain objects or `Optional`, and never leak SQL or ORM types.

## 12. Sequence diagrams

**Hold and pay**

```
User   BookingService   Validator   SeatLockService   ShowSeat   PaymentService   Observers
 │ createBooking │          │              │              │            │              │
 │──────────────►│ validate()►             │              │            │              │
 │               │ lockSeats(sorted ids) ─►│ tryLock() ──►│ (atomic)   │              │
 │               │◄──── hold expires at ───│              │            │              │
 │               │ price, save PENDING ───────────────────────────────────────────────►│ event
 │◄── booking ───│              │              │            │              │
 │ confirmBooking│              │              │            │              │
 │──────────────►│ holdsLock()? ─────────────────────────►│            │              │
 │               │ charge() ──────────────────────────────────────────►│ (may retry)  │
 │               │ confirm() each seat ──────────────────►│ BOOKED     │              │
 │               │ booking → CONFIRMED ───────────────────────────────────────────────►│ email + SMS
 │◄── confirmed ─│
```

**Payment succeeds but the hold lapsed (the dangerous path)**

```
BookingService: charge() ok → confirm(seat) returns false (lock expired)
              → release any seats already confirmed
              → refund payment
              → booking EXPIRED, throw BookingExpiredException("...refunded")
```

**Cancel:** authorize → `CancellationPolicy.refundFor()` (throws if under 2h) → atomic `booking.cancel()` → release seats → refund → publish.

## 13. Concurrency and thread safety

This is the heart of the problem.

| Race | Solution |
|---|---|
| Two users grab the same seat | `ShowSeat.tryLock()` is `synchronized` check-and-set. One wins, the rest get `SeatUnavailableException` |
| Multi-seat request partially succeeds | `lockSeats` acquires seats **in sorted order** and **rolls back** everything on the first failure. All or nothing |
| Two requests with opposite seat orders `[A1,A2]` vs `[A2,A1]` | Sorted acquisition order means both contend on A1 first, so one wins deterministically. Each lock is also held only for one short call, so there's no deadlock |
| Hold expires between "check" and "confirm" | `confirm()` re-checks expiry atomically inside the seat's lock |
| Double-click on Pay | `AtomicBoolean paymentInProgress` per booking. The second click gets "Payment already in progress" |
| Double-click on Create | Idempotency key plus atomic `putIfAbsent` |
| Cancel and confirm race, or cancel twice | `Booking.transitionTo` is synchronized on the booking, so only one transition wins |
| Sweeper vs confirm | The sweeper skips bookings with a payment in progress, and the confirm path rolls back and refunds if the sweeper won anyway |
| High read traffic on the seat map | Reads are cheap per-seat synchronized checks. Locks are per `ShowSeat`, never global, so different shows and seats never contend |

**Lazy expiry** is a key design choice. Every `ShowSeat` access checks `now >= lockExpiry` and frees the seat if so. So correctness never depends on the sweeper running on time, and the sweeper is only housekeeping.

**Distributed version:** with several app servers the per-seat monitor no longer works. Use a Redis `SET seat:{show}:{seat} userId NX PX 600000` (atomic, with TTL built in) or a DB conditional update such as `UPDATE show_seats SET status='LOCKED', holder=?, expiry=? WHERE show_id=? AND seat_id=? AND (status='AVAILABLE' OR expiry < now())`. A row count of 0 means someone else has it.

## 14. Error handling and edge cases

```java
abstract class BookingException extends RuntimeException
 ├─ InvalidRequestException       ├─ SeatUnavailableException
 ├─ BookingExpiredException       ├─ IllegalBookingStateException
 ├─ PaymentFailedException        ├─ NotFoundException
 ├─ AccessDeniedException         └─ ShowConflictException
```

| Edge case | Handling |
|---|---|
| Seat taken by someone else | `SeatUnavailableException`, with rollback of other seats in the request |
| User abandons the payment page | Hold expires, the seat returns to sale automatically |
| Payment gateway declines | The booking stays `PENDING`, so the user can retry until the hold expires |
| Payment succeeds but the hold lapsed during payment | Roll back, **refund**, throw `BookingExpiredException` |
| Gateway timeout with unknown outcome | In production: reconciliation job comparing gateway records with bookings (a pending payment never silently loses money) |
| Double-tap or network retry | Idempotency key returns the same booking |
| Confirm called twice | Returns the already-confirmed booking, with no second charge |
| Cancel within 2h of show | `IllegalBookingStateException`, no refund path |
| Observer (email) throws | Caught and logged, the booking is unaffected |
| Show cancelled by theatre | Admin flow: cancel all confirmed bookings with 100% refund (extension) |
| Two shows overlapping on a screen | `ShowConflictException` at scheduling time |

## 15. Validation

| Layer | Checks |
|---|---|
| **Value objects / entities** | `Money` non-negative, `Movie` duration > 0, screen has seats |
| **BookingValidator** (service) | 1 to 6 seats, no duplicates, show not started, seats exist in this show, no other live pending booking by this user for this show |
| **Domain state** | `Booking.transitionTo` (legal transitions only) |
| **Authorization** | Every confirm/cancel checks `booking.user == caller` |
| **Scheduling** | Overlap and cleaning-buffer check |

The "one live pending booking per user per show" rule isn't only business policy. It also protects correctness, because rolling back a failed multi-seat request can't release a lock that belongs to the same user's other pending booking.

## 16. Extensibility

| Change | How | Existing code touched |
|---|---|---|
| Festival or dynamic pricing | New `PricingStrategy` decorator | None |
| Occupancy-based surge | Decorator reading seat map fill % | None |
| New payment method (wallet, BNPL) | New `PaymentMethod` plus factory entry | Factory only |
| Different refund rules | New `CancellationPolicy` | Constructor wiring |
| Promo / coupon codes | `PromoPricing` decorator | None |
| Food and beverage add-ons | `BookingItem` list on `Booking` plus a pricing step | Booking |
| Seat categories with different layouts (recliners, couples) | New `SeatType` value and layout rows | Enum only |
| Waitlist for sold-out shows | Observer on seat release events | None |
| Group or corporate bookings | Raise `MAX_SEATS_PER_BOOKING` via config strategy | Validator |

## 17. Data structures and algorithms

| Need | Choice |
|---|---|
| Seat lookup in a show | `LinkedHashMap<String, ShowSeat>` built once (fixed keys, O(1) lookup, stable display order) |
| Seat ordering | `Seat implements Comparable` (row, then number) |
| Lock ordering | Sorted seat ids, so acquisition order is globally consistent |
| Overlap detection | Interval check against shows on the same screen: `a.start < b.end && b.start < a.end` (add buffer to `end`) |
| Idempotency | `ConcurrentHashMap.putIfAbsent` |
| Expiry | Lazy check per access, plus a periodic scan (production: a delay queue or DB index on `hold_expires_at` where status is PENDING) |
| Search | Filter by city, movie, date. At scale: an index on `(city, movie, start_time)`, or Elasticsearch for text search on titles |

## 18. API design

| Method | Endpoint | Body → Response |
|---|---|---|
| GET | `/cities/{id}/movies` | → `[movie]` |
| GET | `/shows?city=&movie=&date=` | → `[show]` |
| GET | `/shows/{id}/seats` | → `[{seatId, type, status, price}]` |
| POST | `/bookings` + `Idempotency-Key` | `{showId, seatIds[]}` → `201 {bookingId, amount, holdExpiresAt}` / `409` if a seat is taken |
| POST | `/bookings/{id}/confirm` | `{paymentMethod}` → `200 booking` / `410` if expired / `402` if payment failed |
| POST | `/bookings/{id}/cancel` | → `200 {refund}` / `409` if too late |
| GET | `/users/{id}/bookings` | → `[booking]` |
| POST | `/admin/shows` | `{movieId, screenId, startTime, basePrice}` → `201` / `409` on overlap |

Service signatures: `createBooking(CreateBookingRequest)`, `confirmBooking(bookingId, userId, method)`, `cancelBooking(bookingId, userId)`, `expireStaleBookings()`.

## 19. Database schema

```sql
CREATE TABLE cities    (id VARCHAR PRIMARY KEY, name VARCHAR NOT NULL);
CREATE TABLE theatres  (id VARCHAR PRIMARY KEY, name VARCHAR, city_id VARCHAR REFERENCES cities(id));
CREATE TABLE screens   (id VARCHAR PRIMARY KEY, theatre_id VARCHAR REFERENCES theatres(id), name VARCHAR);
CREATE TABLE seats     (id VARCHAR, screen_id VARCHAR REFERENCES screens(id),
                        row_label VARCHAR, number INT, type VARCHAR,
                        PRIMARY KEY (screen_id, id));
CREATE TABLE movies    (id VARCHAR PRIMARY KEY, title VARCHAR, duration_min INT, language VARCHAR, genre VARCHAR);

CREATE TABLE shows (
  id VARCHAR PRIMARY KEY, movie_id VARCHAR REFERENCES movies(id),
  screen_id VARCHAR REFERENCES screens(id),
  start_time TIMESTAMP NOT NULL, end_time TIMESTAMP NOT NULL, base_price NUMERIC(10,2));

CREATE TABLE show_seats (
  show_id VARCHAR REFERENCES shows(id), seat_id VARCHAR,
  status VARCHAR NOT NULL,                       -- AVAILABLE | LOCKED | BOOKED
  holder_id VARCHAR, lock_expiry TIMESTAMP,
  booking_id VARCHAR,
  PRIMARY KEY (show_id, seat_id));

CREATE TABLE bookings (
  id VARCHAR PRIMARY KEY, user_id VARCHAR NOT NULL, show_id VARCHAR REFERENCES shows(id),
  status VARCHAR NOT NULL, amount NUMERIC(10,2), refund NUMERIC(10,2) DEFAULT 0,
  hold_expires_at TIMESTAMP, created_at TIMESTAMP,
  idempotency_key VARCHAR UNIQUE);

CREATE TABLE booking_seats (booking_id VARCHAR REFERENCES bookings(id), seat_id VARCHAR,
                            PRIMARY KEY (booking_id, seat_id));

CREATE TABLE payments (id VARCHAR PRIMARY KEY, booking_id VARCHAR REFERENCES bookings(id),
                       amount NUMERIC(10,2), method VARCHAR, status VARCHAR, txn_id VARCHAR);

-- Indexes
CREATE INDEX idx_shows_lookup ON shows(movie_id, start_time);
CREATE INDEX idx_shows_screen ON shows(screen_id, start_time);
CREATE INDEX idx_bookings_user ON bookings(user_id, created_at DESC);
CREATE INDEX idx_bookings_expiry ON bookings(hold_expires_at) WHERE status = 'PENDING';
```

**Database-level double-booking guard** (the primary key `(show_id, seat_id)` plus a conditional update):

```sql
UPDATE show_seats SET status='LOCKED', holder_id=?, lock_expiry=?
WHERE show_id=? AND seat_id=?
  AND (status='AVAILABLE' OR (status='LOCKED' AND lock_expiry < now()));
-- rows affected = 0  →  someone else holds it
```

For a multi-seat hold, run the updates inside one transaction. If any affects 0 rows, roll the whole transaction back (the SQL version of the in-memory rollback).

## 20. Notifications and events

```java
record BookingEvent(String bookingId, BookingStatus status, Instant at, Booking booking) {}
```

| Event | Customer sees | Other consumers |
|---|---|---|
| PENDING (created) | "Complete payment within 10 min" | Analytics (funnel) |
| CONFIRMED | Email with ticket plus SMS | Billing, theatre dashboard |
| CANCELLED | Cancellation and refund notice | Refund workflow, waitlist |
| EXPIRED | "Your hold expired" | Analytics (drop-off) |

Observers run synchronously and are wrapped in try/catch. At scale, `BookingService` publishes the same events to a broker (Kafka), and the notification, analytics and billing services consume them independently.

## 21. Logging and monitoring

**Logs:** one structured line per transition (`booking=booking-7 status=CONFIRMED seats=[A1, A2]`), plus lock failures, payment attempts, refunds and sweeper counts. Don't log card data or full phone numbers.

| Metric | Why |
|---|---|
| Hold-to-confirm conversion rate | Core funnel health |
| Seat-lock conflicts per show | Contention and "hot show" detection |
| Expired holds | Abandonment, and whether the TTL is right |
| Payment failure rate and latency (p95/p99) | Gateway health |
| Refund volume | Cancellation behavior, revenue leakage |
| Auto-refunds after lapsed hold | Signals the TTL or gateway is too slow |
| Sweeper lag | Housekeeping health |

**Alerts:** payment failure spike, auto-refund spike, bookings stuck `PENDING` past their TTL, DB lock wait times.

## 22. Testing strategy

| Level | What | In the file |
|---|---|---|
| Unit | State machine, pricing decorators, validation, lock rollback | yes |
| Concurrency | 50 users race for one seat (exactly 1 wins), opposite seat orders (no deadlock, 20 rounds), 10 users on 10 seats (all succeed) | yes |
| Time-based | Expiry, lazy release, sweeper, cancellation tiers using an injectable `FakeClock` | yes |
| Failure injection | Flaky gateway (retry then succeed), slow gateway that lets the hold lapse (refund path), observer that throws | yes |
| Authorization and idempotency | Other users blocked, same key returns the same booking, double-confirm doesn't double-charge | yes |
| Integration | Real DB with Testcontainers, checking the conditional-update guard | not included |
| Load | A thousand users on one show, asserting no oversell | not included |

Two design choices make this testable: the `Clock` is injected, and `PaymentMethod` is a one-method interface, so a lambda stands in for a flaky or slow gateway.

## 23. Security and authorization

- **Authentication:** OTP or password login, then a short-lived JWT
- **Ownership checks:** confirm and cancel verify `booking.user == caller`. Never trust a `bookingId` alone
- **Admin endpoints:** role-based (ADMIN only) for scheduling and theatre management
- **Anti-abuse (bots grabbing seats):** rate-limit hold requests per user and IP, cap seats per booking, one live pending booking per user per show, CAPTCHA on hot shows
- **Payment security:** tokenize cards through the gateway (never store PAN), TLS everywhere, verify the amount server-side from the booking, never from the client
- **Idempotency keys** also protect against replayed requests
- **Audit:** immutable log of state changes and refunds

## 24. Trade-offs and future scope

| Decision | Trade-off |
|---|---|
| Lock first, then pay | Prevents paying for unavailable seats, but holds inventory for 10 minutes. A shorter TTL frees seats faster but rushes users |
| Lazy plus sweeper expiry | Correct even if the sweeper is late. Costs a tiny check on every read |
| Per-seat monitors in-process | Fast and simple, but single-node only. Redis `SET NX PX` or conditional SQL is needed across servers |
| All-or-nothing multi-seat holds | Predictable, but a single taken seat fails the whole request. The UI should re-offer nearby alternatives |
| Refund on lapsed hold | Safe for the customer, but costs gateway fees. A TTL extension while payment is in flight is an alternative |
| Synchronous observers | Simple, but a slow email provider could slow the response. Move to an event bus when volume grows |
| Seat map polling | Easy, but stale. WebSockets or SSE can push live seat changes |

**Future scope:**
- Dynamic pricing from occupancy
- Waitlists and "notify me" for sold-out shows
- Multi-seat "best available" auto-selection
- Show cancellation workflow with bulk refunds
- Partner theatre APIs and inventory sync
- Splitting into services (Catalog, Inventory/Locking, Booking, Payments, Notifications) with an event bus
- Sharding show inventory by `show_id` (consistent hashing from our earlier notes works well here)

## How this maps to the code

| Design section | Where in `BookMyShowApp.java` |
|---|---|
| 4, 3 | Sections 2 and 3: enums, records, entities |
| 5-8 | Sections 4 and 5: interfaces and strategies |
| 9 | `BookingStatus.canMoveTo`, `Booking.transitionTo`, `ShowSeat` |
| 10-11 | Sections 7 and 8: repositories and services |
| 13-14 | `ShowSeat`, `SeatLockService`, `BookingService.confirmBooking` |
| 20-21 | Section 6: observers and `Log` |
| 22 | Section 11: 19 self tests |

If you'd like, I can split this into a Maven project with packages and JUnit 5 tests, add a Redis-style distributed lock abstraction, or write the next classic problem (parking lot, Splitwise or an elevator system) in the same format.
