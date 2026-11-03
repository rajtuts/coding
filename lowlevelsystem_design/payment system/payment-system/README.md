# Payment system: Java + H2

A runnable low-level implementation of the payment gateway design: plain JDBC, no framework, H2 in-memory (PostgreSQL mode).

## Run

    mvn -q compile exec:java          # Java 17+, Maven 3.8+

The `Main` class runs 11 scenarios and prints what happened (idempotent replays, concurrent duplicates,
PSP timeouts, webhooks, circuit-breaker failover, concurrent refunds, outbox redelivery, ledger integrity).

## Layout (package com.example.payments)

| Class | Role |
|---|---|
| `PaymentService` | Create / capture flow in 3 short transactions, never holding a DB tx across a PSP call. `applyPspResult` is the single path that applies any outcome (API, webhook, sweeper). |
| `PaymentStatus` | State machine and the legal-transition table |
| `PaymentRepository` | JDBC + row locks (`FOR UPDATE`) + optimistic `version` CAS + `payment_events` audit |
| `IdempotencyRepository` | Key reservation (PK), request-hash check, stored response replay |
| `LedgerService` | Append-only double-entry postings, deterministic txn ids, balance checks |
| `RefundService` | Refund request under a payment row lock, then worker that calls the PSP and books the ledger |
| `WebhookService` | HMAC verification, inbox dedupe, applied in the same tx as the inbox insert |
| `SweeperService` | Resolves PROCESSING / UNKNOWN payments by asking the PSP |
| `OutboxRelay`, `InMemoryBroker` | Transactional outbox, at-least-once publish, idempotent consumer |
| `PspAdapter`, `MockPspAdapter`, `PspRouter`, `CircuitBreaker` | Provider abstraction, simulated PSP, routing and failover |
| `RiskService` | Pre-authorization rules |

## Mock PSP tokens

`pm_ok` approve, `pm_declined` decline, `pm_timeout` PSP charges but the response is lost,
`pm_timeout_lost` PSP never received the request, `pm_stolen*` risk decline.

## Moving to PostgreSQL

* Swap the JDBC URL and add the driver; the SQL is deliberately portable.
* Use `FOR UPDATE SKIP LOCKED` in `OutboxRelay` / `SweeperService` queries to run several workers.
* Make `psp_reference` uniqueness a partial index (`WHERE psp_reference IS NOT NULL`).
* Add a deferred constraint trigger (or a periodic job) enforcing debits = credits per ledger txn;
  revoke UPDATE/DELETE on `ledger_entries`.
* Replace the mock PSP with real adapters and the in-memory broker with Kafka/SQS.

## Deliberately out of scope

HTTP layer, auth, void/partial capture, multi-currency, settlement reconciliation, payouts.
