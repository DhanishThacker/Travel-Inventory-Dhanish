# Architecture

Spring Boot 3 / Java 17, plain `JdbcTemplate` (explicit SQL, so locks and constraints are visible), file-backed
H2 in the default config. Every rule that has to hold under concurrency is enforced **in the database** (constraints,
row locks, transactions) — never in process memory. The SQL is deliberately portable; for production I would run the
same schema on PostgreSQL (see *What I would do next*).

```
Controller (HTTP only) -> Service (use-case + DB transaction boundary) -> Repository (SQL)
                              |-> DuplicateIdentityGuard   what "the same transaction" means
                              |-> MetadataValidators       one validator per transaction type
                              '-> InvoiceRollupCalculator  pure function, no DB
```

## Tables and models

**`transactions`** — one row per transaction, any type: `id`, `external_id` (UNIQUE), `type`, `currency`,
`occurred_at`, `total DECIMAL(19,2)`, `metadata`, timestamps. **Taxes and fees are real child tables**, kept apart
because they are different things (government tax vs carrier/merchant fee) and roll up separately:
`transaction_tax_lines(transaction_id, line_no, name, rate, amount)` and `transaction_fee_lines(transaction_id, line_no, name, amount)`,
both `ON DELETE CASCADE`.

**Type-specific data** is a JSON document in `transactions.metadata`, *validated per type on every write*
(`FlightMetadataValidator`, `HotelMetadataValidator`, `RailMetadataValidator`, `NavanFeeMetadataValidator`), so it is never an
unchecked blob:

| type | metadata must contain |
|---|---|
| `flight` | `origin`, `destination`, `fare`, `coupons[]` (each: `flight_number`, `from`, `to`, `departure`, `arrival`, optional `carrier`) |
| `hotel` | `name`, `address{street,city,country}`, `check_in`, `check_out`, `nights`, `room_rate_per_night`, optional `extras[]` |
| `rail` | `legs[]` (each: `origin`, `destination`, `departure`, `arrival`, optional `train_number`, `class`) — a change of train is two legs |
| `navan_fee` | `fee_kind` ∈ {`trip_fee`, `agent_call_fee`}, optional `description`, `related_external_id` |

*Why a JSON column and not a table per type (or STI)?* The four types share identity, money, taxes and fees — everything
the service *queries, locks or sums*. Only the descriptive part differs, and nothing here filters or joins on it, so a validated
document keeps GET round-tripping the exact structure with one table. The cost: you cannot index into `metadata`. If the
product needs "all flights from TXL", I would promote those fields to columns (or per-type tables) behind the same API.

## `external_id` uniqueness and concurrent POSTs

`UNIQUE (external_id)` on `transactions` is the real guarantee. `DuplicateIdentityGuard` adds a cheap pre-check (the common
retry never reaches the write path) and then runs the insert in its own DB transaction. If two requests race past the
pre-check, the database lets exactly one INSERT win; the loser gets a constraint violation which the guard catches **outside**
the failed transaction, re-reads the winner's id, and returns **409 with `existing_id`**. Header + tax lines + fee lines + metadata
are written in one DB transaction (all or nothing). Covered by a test firing 8 simultaneous POSTs: one 201, seven 409, one row.

Duplicate detection runs *before* deep metadata validation so a retried booking always gets the stored id back
(the provided `duplicate_flight` fixture has an empty `coupons` array and must still be a 409, not a 400).

## Invoice snapshot

`POST /invoices` is one DB transaction: lock the source rows, read their current values, **copy** them. The invoice owns
`invoice_lines` (type, total, metadata copy), `invoice_line_tax_lines` / `invoice_line_fee_lines` (copies) and stored
rollups `invoice_tax_rollup` / `invoice_fee_rollup`. `GET /invoices/{id}` reads **only** `invoice_*` tables — it is never a live join over
`transactions`, so editing a transaction (even directly in SQL, which the snapshot test does) cannot change an invoice.
`PATCH /invoices/{id}` is the explicit "re-snapshot": delete the old snapshot and rebuild from current values in one transaction.

Rollup rules: tax lines group by **(name, rate)** (a rate-less tax is its own group), fee lines group by **name**, exact
case-sensitive match, first-seen order. Grand total = sum of transaction totals (gross: each total already includes its own
taxes and fees; rollups are informational). Mixed currencies => 409. The same transaction id twice in one request => 409.

## Update / delete rules and locking

A transaction on **any** invoice cannot be PATCHed or DELETEd (409). To make that race-free, PATCH, DELETE and "add to invoice"
all take the same lock first: `SELECT ... FOR UPDATE` on the transaction row, *then* check `invoice_lines`, then write.
Whichever arrives first wins: either the edit commits and the invoice snapshots the new values, or the invoice commits first and the
edit gets 409. Invoice writes lock multiple transactions in sorted id order, so two writers cannot deadlock. As a backstop,
`invoice_lines.transaction_id` is a RESTRICT foreign key: the database itself refuses to delete an invoiced transaction.
PATCH replaces `tax_lines` / `fee_lines` / `metadata` wholesale under the row lock, so concurrent PATCHes are last-write-wins
but never torn (tested with 6 simultaneous writers). Deleting an invoice cascades through every snapshot table and never touches transactions.

## High volume

What breaks first, and what I did about it:

1. **Duplicate POST storms (retries, double-clicks)** — an application-level "check then insert" races. *Fix:* UNIQUE index on
   `external_id` (also makes the pre-check an index lookup) and conflict handling that returns the winner's id.
2. **Concurrent PATCH / DELETE / invoice-create on the same rows** — lost updates and edits to already-billed money.
   *Fix:* row lock first, invariant check second, write third, all in one transaction; sorted lock order; RESTRICT FK as a backstop;
   a lock timeout (10 s) so a stuck writer fails instead of hanging every client.
3. **Large lists** — an unbounded `SELECT *` and `COUNT(*)` on every page are the classic killers. *Fix:* every list is
   `LIMIT/OFFSET` in SQL (default 50, max 100, larger => 400), ordered by an indexed `(created_at, id)`; no total count — the service
   fetches `limit+1` rows to compute `has_more`. Tax/fee lines for a page load with one `IN (...)` query each (no N+1).
   Invoice list returns headers only.
4. **"Is this transaction invoiced?"** — hit on every PATCH/DELETE. *Fix:* index on `invoice_lines(transaction_id)`.
   Lookups by id are primary-key hits.

Known limits: `OFFSET` gets slower on very deep pages (keyset pagination on `(created_at, id)` is the next step — the index
already supports it); a single embedded H2 file serialises writers more than a server database would.

## Assumptions and trade-offs

- Money is non-negative (no refunds / credit notes); the server does not recompute `total` from its parts (a client-supplied gross figure is stored as given).
- `type`, `external_id`, `currency` are immutable after create (PATCH returns 400 if you send them).
- A transaction may be on several invoices; it is locked while on any.
- No auth, no soft-delete, no audit trail — out of scope for this exercise.

## What I would do next

PostgreSQL (same schema; `FOR UPDATE` and the unique constraint behave the same) with Flyway migrations instead of `schema.sql`;
keyset pagination; `GET /transactions?type=&from=&to=` filters backed by composite indexes; an `Idempotency-Key` header;
a test that kills a request halfway through the insert to prove the atomicity under failure injection.
