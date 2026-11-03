# Payment System 
# High-level overview

The code is one Java package with plain JDBC and no framework. It splits into four layers, and everything above the database talks to it through short transactions.

```
Main (demo driver)
   │
Services        PaymentService · RefundService · WebhookService · SweeperService · OutboxRelay
   │
Domain/Support  PaymentStatus (state machine) · LedgerService · RiskService · PspRouter/CircuitBreaker
   │
Persistence     Database (tx helper) · Payment/Idempotency/Refund/Outbox repositories · schema.sql
   │
External        PspAdapter ← MockPspAdapter
```

## How a payment moves through the system

1. **Reserve.** `PaymentService.createPayment` stores the idempotency key and a `CREATED` payment in one transaction. A repeat of the same request returns the saved response, and a repeat with a different body is rejected.
2. **Claim.** The payment row is locked, the risk check runs, a PSP is chosen, and the state moves to `PROCESSING`.
3. **Call the PSP.** This happens with no database transaction open. A timeout is treated as "unknown", never as a failure.
4. **Apply the outcome.** The state change, ledger entries, outbox event, and idempotency response are saved atomically.

Steps 2 and 4 are the only places where state changes, and step 4's logic lives in a single method, `applyPspResult`. The sync API call, an inbound webhook, and the sweeper all go through it. A stale or duplicate signal is dropped by the transition table in `PaymentStatus` rather than by special-case code.

## What each part is responsible for

| Part | Responsibility |
|---|---|
| `PaymentStatus` | The only definition of which state transitions are legal |
| `PaymentRepository` | Row locks, version check (optimistic locking), audit trail in `payment_events` |
| `LedgerService` | Append-only double-entry postings. Each posting must balance, and its id comes from the business event, so it can't be posted twice |
| `RefundService` | Records a pending refund under a payment lock, so concurrent requests can't over-refund. A worker later calls the PSP and books the ledger |
| `WebhookService` | Verifies the signature, dedupes by event id, then applies the event |
| `SweeperService` | Finds payments stuck in `PROCESSING` or `UNKNOWN` and asks the PSP what happened |
| `OutboxRelay` | Publishes events written in the same transaction as the state change. Delivery is at least once, so the consumer dedupes |
| `PspRouter` + `CircuitBreaker` | Chooses a PSP for new payments and skips one whose circuit is open. Existing payments stay with their PSP |
| `MockPspAdapter` | Simulated PSP, idempotent on our reference, with tokens that trigger declines and timeouts |

## The design ideas the code is built around

- **Never hold a transaction across a network call.** Each PSP call sits between two short transactions.
- **Uncertainty is a state.** A timeout puts the payment in `UNKNOWN`. It is resolved later by a webhook or the sweeper, never guessed.
- **Every duplicate-prone path has a database-level guard.** That means primary keys for idempotency keys and webhooks, deterministic ledger transaction ids, and row locks for refunds.
- **The ledger is the source of truth.** Money is never updated in place. Balances are derived, and a check confirms that every transaction balances and the trial balance is zero.

`Main` wires the pieces together and runs the 11 scenarios, each one exercising one of these guarantees. I can go deeper on any single piece, such as the transaction boundaries in `PaymentService` or the refund concurrency path.

# Detailed code overview

The project is `com.example.payments`, 26 Java classes plus `schema.sql`. It uses plain JDBC and H2 (PostgreSQL mode), with no framework and no libraries beyond the H2 driver.

## 1. Database schema (`schema.sql`)

Most of the correctness guarantees are enforced here, so the application code can rely on them.

| Table | Purpose | Key constraints |
|---|---|---|
| `payments` | One row per payment, with status, PSP name and reference, and a `version` counter | `UNIQUE(merchant_id, merchant_order_id)` stops duplicate orders. `UNIQUE(psp, psp_reference)` stops two payments claiming one PSP transaction. Index on `(status, updated_at)` serves the sweeper. |
| `idempotency_keys` | Stores request hash, status (`IN_PROGRESS` or `COMPLETED`), the saved response, and the linked `payment_id` | PK `(merchant_id, idem_key)` |
| `payment_events` | Audit trail of every state transition and who caused it | |
| `refunds` | One row per refund request | `UNIQUE(payment_id, idempotency_key)` |
| `accounts`, `ledger_transactions`, `ledger_entries` | The double-entry ledger | Entries have a direction check (`D`/`C`), `amount > 0`, and foreign keys to accounts and transactions. Transaction ids are primary keys. |
| `outbox` | Events waiting to be published | Index on `(published, id)` |
| `webhook_inbox` | Seen webhook events | PK `(psp, event_id)` |

All amounts are `BIGINT` in minor units (paise). I avoided H2 reserved words, so the column is `idem_key`, not `key`.

## 2. Infrastructure classes

- **`Database`** hands out one connection per transaction. `inTx(work)` turns off autocommit, commits on success, and rolls back on any `SQLException` or `RuntimeException`. `execute(work)` is the void version. SQL errors are wrapped in `DataAccessException`, which can tell you whether the error was a unique violation (SQLSTATE 23505) or a concurrency conflict (lock timeout, concurrent update, or deadlock). `initSchema()` reads `schema.sql`, strips comment lines, and runs each statement.
- **`Ids`** creates prefixed, roughly time-sortable ids such as `pay_…` and `rf_…`.
- **`Json`** is a minimal JSON writer, used for API responses, outbox payloads, and audit metadata.
- **`Crypto`** provides SHA-256 (for request hashing), HMAC-SHA256 (for webhook signatures), and a constant-time string comparison.
- **`ApiException`** carries an HTTP-style status and a code. It is how business errors such as 409, 422, and 404 surface.

## 3. Domain model

- **`PaymentStatus`** holds the state machine as a transition table:

```
CREATED    → PROCESSING, FAILED
PROCESSING → AUTHORIZED, CAPTURED, FAILED, UNKNOWN
UNKNOWN    → AUTHORIZED, CAPTURED, FAILED
AUTHORIZED → CAPTURED, VOIDED, FAILED
CAPTURED   → PARTIALLY_REFUNDED, REFUNDED
PARTIALLY_REFUNDED → PARTIALLY_REFUNDED, REFUNDED
```

  `external()` hides internal in-flight states, so `CREATED` and `UNKNOWN` are reported to merchants as `PROCESSING`.
- **`Payment`** and **`Refund`** are immutable records mirroring their tables.

## 4. Repositories

Every repository method takes a `Connection`, so several repositories can join one transaction.

- **`PaymentRepository`**
  - `find(id, forUpdate)` uses `SELECT … FOR UPDATE` to take a row lock.
  - `transition(...)` checks the transition table, then runs `UPDATE … WHERE id=? AND version=?` (an optimistic compare-and-set). It then inserts an audit event and returns the fresh row.
  - `annotate(...)` sets the PSP name, PSP reference, or failure code without bumping the version. It uses `COALESCE(CAST(? AS VARCHAR), col)` so null arguments leave the existing value alone.
  - `findStuck(...)` returns payments in `PROCESSING` or `UNKNOWN` that haven't changed recently.
- **`IdempotencyRepository`**
  - `insert` throws on a duplicate key, which is how a replay is detected.
  - `completeByPaymentId` only updates rows still `IN_PROGRESS`, so it is safe to call from any settlement path.
- **`RefundRepository`** has lookup by `(payment, key)`, `findPending`, `sumActive` (PENDING plus SUCCEEDED, which is money already committed), and `sumSucceeded`.
- **`OutboxRepository`** has `add`, `fetchUnpublished`, and a batched `markPublished`.

## 5. `LedgerService`

- **Accounts** are opened at onboarding (`openMerchantAccount`, `openPspAccount`, `openPlatformAccounts`). Postings never create accounts, which avoids races on first use.
- **Capture** posts: debit `psp:X:receivable` (gross), credit `merchant:M:payable` (net), and credit `platform:fee_revenue` (2% fee, in basis points).
- **Refund** posts the mirror image. The fee returned is proportional to the refund amount. The final refund returns exactly the remaining fee (`total fee − already reversed`), so rounding never leaves stray paise.
- **`post(...)`** checks that debits equal credits, skips zero-amount lines, and skips the posting if the transaction id already exists. Ids look like `CAPTURE:{paymentId}` and `REFUND:{refundId}`.
- **Read side**: `entries`, `entryCountForPayment`, `balances` (debit minus credit per account), and `findUnbalancedTransactions` (should always return empty).

## 6. PSP layer

- **`PspAdapter`** is the provider interface with `authorize`, `capture`, `refund`, and `getStatus`. Results carry one of these statuses: `AUTHORIZED`, `CAPTURED`, `REFUNDED`, `FAILED`, `PENDING`, `NOT_FOUND`, `UNKNOWN`.
- **`PspException`** has an `outcomeUnknown` flag. A read timeout sets it to true, because the PSP may have processed the request. Connection refused sets it to false, because the request never left.
- **`MockPspAdapter`** is a simulated PSP that is idempotent on our reference. Its behavior is driven by the payment token:
  - `pm_ok` approves.
  - `pm_declined` declines.
  - `pm_timeout` charges the card but throws a timeout, so we never see the result.
  - `pm_timeout_lost` throws a timeout without ever recording the request.
  - `setDown(true)` simulates an outage.
- **`CircuitBreaker`** opens after N consecutive failures. After a cool-down it lets traffic through again (half-open) until a success or failure is recorded.
- **`PspRouter`** picks the first PSP, in priority order, whose circuit is closed. This applies to new payments only. Operations on existing payments go to their own PSP via `byName`.

## 7. `PaymentService`, the core

### `createPayment(request, idempotencyKey)`

1. **Validate** the request, then compute a SHA-256 hash of its canonical form.
2. **Transaction 1 (reserve):** insert the idempotency key, then the `CREATED` payment and its audit event.
   - If the key already exists and the hash differs, return 422.
   - If the key exists, the hash matches, and it is `COMPLETED`, replay the stored response.
   - If the key exists but is `IN_PROGRESS`, return 409.
   - If `merchant_order_id` was already used, return 409. This rolls back the idempotency row as well, so the key isn't consumed.
   - A lock or concurrency conflict from the database also becomes 409.
3. **Transaction 2 (claim):** lock the payment, then run the risk check. A decline fails the payment with `RISK_DECLINED`. If no PSP circuit is closed, it fails with `NO_PSP_AVAILABLE`. Otherwise it records the chosen PSP and moves the payment to `PROCESSING`.
4. **No transaction: call the PSP.** On success the circuit breaker records a success. A timeout maps to `UNKNOWN`. A refused connection maps to `FAILED` with `PSP_UNAVAILABLE`. Both record a breaker failure.
5. **Transaction 3 (settle):** call `applyPspResult` and return the response.

### `applyPspResult(connection, paymentId, result, actor)`

This is the single path for applying any outcome.

- It locks the payment row and maps the PSP status to a target state.
- It **ignores** the result if the target is null, equal to the current state, or not a legal transition. This is how stale, duplicate, and out-of-order signals are neutralized.
- Otherwise it records the PSP reference and failure code, transitions the state, posts the capture ledger entries if the new state is `CAPTURED`, writes an outbox event (except for `UNKNOWN`, which is internal), and completes the idempotency record once the payment is out of `CREATED` and `PROCESSING`.

### `capture(paymentId, amount)`

This is the second step of authorize-then-capture. It requires `AUTHORIZED` and the full amount. If the PSP call fails, the payment stays `AUTHORIZED` and the caller gets a 504, so the capture can be retried safely.

## 8. `RefundService`

- **`requestRefund`** runs in one transaction under a row lock on the payment, which serializes concurrent refunds. It returns the existing refund if the same key is replayed. It rejects the request unless the payment is `CAPTURED` or `PARTIALLY_REFUNDED`, and rejects it if `PENDING + SUCCEEDED + requested` exceeds the captured amount. Otherwise it inserts a `PENDING` refund and writes an outbox event.
- **`processPending`** is the worker. For each pending refund it calls the PSP with no transaction open, using the refund id as the PSP idempotency key. If the call throws, the refund stays `PENDING` and the next run retries it. If the PSP succeeds, one transaction marks the refund `SUCCEEDED`, posts the ledger entries, moves the payment to `PARTIALLY_REFUNDED` or `REFUNDED`, and writes the outbox event. If the PSP reports `FAILED`, the refund is marked `FAILED`, which frees the amount for a new attempt.

## 9. `WebhookService`

- **Wire format:** `eventId|type|reference|pspReference`, with an HMAC-SHA256 signature of the body. This avoids needing a JSON parser.
- **`handle`:** verify the signature (401 if wrong), then in one transaction:
  1. Insert into `webhook_inbox`. If that's a duplicate, return "duplicate".
  2. Map the event type to a PSP status.
  3. Find the payment by our reference (the payment id), falling back to the PSP reference.
  4. Call `applyPspResult`.
- Because dedupe and apply share a transaction, a failure while applying also rolls back the inbox row, so the PSP's retry is processed instead of being dropped.
- It returns "processed" if the state changed (version bumped), or "no-op" if the event was stale.

## 10. `SweeperService`

`run(minAge, failNotFoundAfter)` loads stuck payments and asks the owning PSP what happened via `getStatus(paymentId)`.

- If the PSP has a definite answer, it applies it through `applyPspResult`.
- If the PSP says `NOT_FOUND`, it fails the payment only once the payment is older than `failNotFoundAfter`. This guards against PSP lookups that lag behind.
- If the PSP can't be reached, it leaves the payment alone.
- It returns a `Report` with checked, resolved, failedAsNotFound, and stillPending counts.

## 11. Outbox: `OutboxRelay` and `InMemoryBroker`

`pollOnce` reads unpublished rows, publishes them, then marks them published, so delivery is at least once. A flag simulates a crash after publishing but before marking, which causes redelivery. `InMemoryBroker` records every delivery but only processes each message id once, standing in for an idempotent consumer.

## 12. `Main`, the demo driver

`setup()` wires all objects together, opens the ledger accounts, and creates two mock PSPs (`alpha` and `beta`, with a circuit threshold of 3). The 11 scenarios then run in order, each demonstrating one guarantee. Small helpers (`pay`, `count`, `history`, `printRows`) keep the scenarios short.

## 13. Which mechanism guards which risk

| Risk | Guard |
|---|---|
| Client retries a request | Idempotency key, request hash, stored response |
| Two workers on one payment | `FOR UPDATE` row lock, plus version CAS |
| Double ledger posting | Deterministic transaction id (primary key) |
| Over-refunding | Payment row lock around the sum-and-insert |
| Duplicate or out-of-order webhooks | Inbox primary key, plus the transition table |
| PSP timeout | `UNKNOWN` state, then sweeper or webhook |
| Event lost or invented | Outbox written in the same transaction as the state change |
| PSP outage | Circuit breaker and router failover |
| Crash mid-request | Sweeper finishes the payment and completes the idempotency key |

## 14. Limitations

- **Not run on H2 yet.** Only compilation and non-database logic were checked.
- **Cached `PROCESSING` replays.** A timed-out payment completes its idempotency key with a `PROCESSING` response. Replays return that cached response even after the payment resolves, so merchants should poll `GET` or use webhooks for the final state.
- **No same-request retry on outage.** A payment that hits a refused connection fails outright. Only later payments fail over to the next PSP.
- **Single-instance workers on H2.** The sweeper and outbox relay lack `SKIP LOCKED`, so run one instance of each. On PostgreSQL, add it.
- **Unused or missing features.** `VOIDED` exists in the state machine but has no API. Partial capture, settlement and reconciliation (the `platform:bank_cash` account is opened but unused), and idempotency-key expiry are not implemented.
- **Refund retries are unbounded.** A refund stuck on a PSP error stays `PENDING` and is retried every run, with no maximum attempts.

I can walk through any one flow line by line next, such as the three-transaction create path or the concurrent refund path.
