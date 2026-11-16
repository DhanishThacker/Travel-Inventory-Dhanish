# travel-inventory

Small HTTP service that stores **flight, hotel, rail and Navan-fee transactions** and builds
**frozen snapshot invoices** from them. Spring Boot 3 · Java 17 · file-backed H2 (SQL, real constraints).

No LLM and no API keys are used anywhere. Nothing secret is in the repo.

## Run it (one command)

Requires **JDK 17+** and **Maven 3.9+**.

```bash
mvn spring-boot:run
```

The API is on <http://localhost:8080>. Data is stored in `./data/travel-inventory.mv.db` (a real file,
git-ignored), so it survives restarts. Delete the `data/` folder to start clean.

## Run the tests (one command)

```bash
mvn test
```

Run from the project root (the tests read `fixtures/task-b/*.json`). The tests boot the real app on a random
port against a file-backed H2 database in a temp directory and call it over real HTTP.
They cover every case the brief lists, plus the races:

| # | Brief | Test |
|---|---|---|
| 1 | create each type, GET round-trips metadata | `TransactionApiTest.createsAllFiveSeedsAndGetReturnsTypeSpecificMetadata` |
| 2 | duplicate `external_id` | `TransactionApiTest.duplicateExternalIdDoesNotInsertSecondRowAndReturnsExistingId` |
| 3 | trip invoice matches `expected-invoice.json` | `InvoiceApiTest.tripInvoiceHasLinesTotalsAndRollupsMatchingTheExpectedFixture` |
| 4 | invoice is a frozen snapshot | `InvoiceApiTest.invoiceIsAFrozenSnapshotOfTheTransactionAtInvoiceTime` |
| 5 | mixed currency => 409 | `InvoiceApiTest.mixedCurrency*` |
| 6 | concurrent duplicate create => one row | `TransactionApiTest.concurrentCreatesWithSameExternalIdLeaveExactlyOneRow` |
| 7 | PATCH invoiced transaction => 409 | `TransactionApiTest.patchAfterInvoicedReturns409AndNothingChanges` |
| 8 | DELETE invoiced transaction => 409 | `TransactionApiTest.deleteAfterInvoicedReturns409AndRowStillExists` |
| 9 | DELETE invoice keeps transactions | `InvoiceApiTest.deleteInvoiceRemovesSnapshotButNeverTheTransactions` |
| 10 | pagination bounded | `TransactionApiTest.listIsPaginatedWithDefaultOf50AndRejectsHugeLimits` |
| + | concurrent PATCH never tears tax/fee lists | `TransactionApiTest.concurrentPatchesNeverTearTaxAndFeeLists` |
| + | PATCH vs "create invoice" race is serialised | `InvoiceApiTest.raceBetweenPatchAndInvoiceCreateIsSerialised` |

## API

| Method | Path | Notes |
|---|---|---|
| `POST` | `/transactions` | 201; **409** `{"error":"duplicate_external_id","existing_id":"txn_..."}` if `external_id` exists; 400 on invalid input |
| `GET` | `/transactions/{id}` | 200 / 404 |
| `GET` | `/transactions?limit=50&offset=0` | `limit` default 50, **max 100** (above => 400). Returns `{items, limit, offset, has_more}` |
| `PATCH` | `/transactions/{id}` | 200 / 404 / **409 if the transaction is on any invoice**. Fields: `total`, `occurred_at`, `tax_lines`, `fee_lines`, `metadata` (a provided list/object *replaces* the old one). `type`, `external_id`, `currency` are immutable (400) |
| `DELETE` | `/transactions/{id}` | 204 / 404 / **409 if on any invoice**. Tax and fee lines are deleted with it, atomically |
| `POST` | `/invoices` | Body `{"transaction_id": "..."}` or `{"transaction_ids": [...]}` (max 100). 201; 404 if any id is missing; **409** mixed currency or the same id twice |
| `GET` | `/invoices/{id}` | Header, frozen line snapshots, `tax_rollup`, `fee_rollup`, `totals` |
| `GET` | `/invoices?limit=&offset=` | Same pagination; header summaries only |
| `PATCH` | `/invoices/{id}` | Replace the set of ids; rebuilds the snapshot from the **current** stored transactions atomically. 404 / 409 (mixed currency => invoice unchanged) |
| `DELETE` | `/invoices/{id}` | Deletes the invoice and its snapshot. Never touches transactions |
| `GET` | `/health` | `{"status":"UP"}` (also pings the DB) |

Errors always look like `{"error": "<code>", "message": "..."}`.

## Example curls

Start the app first (`mvn spring-boot:run`), then from the project root. The `id` helper pulls the `id` out of a response.

```bash
BASE=http://localhost:8080
H='Content-Type: application/json'
id() { sed -E 's/.*"id":"([^"]+)".*/\1/'; }
```

### Create (each type) — bodies are in `examples/`

```bash
FLIGHT=$(curl -s -X POST $BASE/transactions -H "$H" -d @examples/flight.json         | id)
HOTEL=$( curl -s -X POST $BASE/transactions -H "$H" -d @examples/hotel.json          | id)
RAIL=$(  curl -s -X POST $BASE/transactions -H "$H" -d @examples/rail.json           | id)
TRIPFEE=$(curl -s -X POST $BASE/transactions -H "$H" -d @examples/trip_fee.json      | id)
AGENT=$( curl -s -X POST $BASE/transactions -H "$H" -d @examples/agent_call_fee.json | id)
echo $FLIGHT $HOTEL $RAIL $TRIPFEE $AGENT
```

### Duplicate create (same `external_id`) — only one row exists

```bash
# 409 + the existing id (this fixture even has a different body: empty coupons)
curl -i -X POST $BASE/transactions -H "$H" -d @examples/duplicate_flight.json
# same body again, also 409
curl -i -X POST $BASE/transactions -H "$H" -d @examples/flight.json
# prove one row: exactly 1 match
curl -s "$BASE/transactions?limit=100" | grep -o '"external_id":"flight-TXL-LHR-2026-03-11"' | wc -l
```

Or run the self-contained script, which posts the same `external_id` twice and counts rows:
`bash scripts/demo.sh`

### Get / list transactions

```bash
curl -s $BASE/transactions/$FLIGHT
curl -s "$BASE/transactions?limit=2&offset=0"      # has_more tells you if there is a next page
curl -i "$BASE/transactions?limit=1000"            # 400: limit is capped at 100
```

### Patch / delete a transaction (and the 409 when invoiced)

```bash
# not invoiced yet => allowed
curl -s -X PATCH $BASE/transactions/$FLIGHT -H "$H" -d '{"total": 230.00, "tax_lines":[{"name":"Airport tax","amount":18.00}]}'

# invoice the trip (see below), then:
curl -i -X PATCH  $BASE/transactions/$FLIGHT -H "$H" -d '{"total": 999.00}'   # 409 transaction_invoiced
curl -i -X DELETE $BASE/transactions/$FLIGHT                                  # 409 transaction_invoiced

# a transaction that is NOT on an invoice can be deleted (its tax/fee lines go with it)
SCRATCH=$(curl -s -X POST $BASE/transactions -H "$H" -d '{"type":"navan_fee","external_id":"scratch-1","currency":"EUR","occurred_at":"2026-03-11T06:40:00Z","total":5.00,"tax_lines":[],"fee_lines":[],"metadata":{"fee_kind":"trip_fee"}}' | id)
curl -i -X DELETE $BASE/transactions/$SCRATCH                                 # 204
```

### Create an invoice by id

```bash
# single transaction
curl -s -X POST $BASE/invoices -H "$H" -d "{\"transaction_id\": \"$HOTEL\"}"

# a whole trip: flight + hotel + rail + both Navan fees
INV=$(curl -s -X POST $BASE/invoices -H "$H" \
  -d "{\"transaction_ids\": [\"$FLIGHT\",\"$HOTEL\",\"$RAIL\",\"$TRIPFEE\",\"$AGENT\"]}" | id)
```

### Get / list invoices

```bash
curl -s $BASE/invoices/$INV          # grand_total 685.70, tax_rollup, fee_rollup, 5 lines
curl -s "$BASE/invoices?limit=10"
```

### Patch / delete an invoice

```bash
# replace the set of transactions; snapshot rebuilt from the CURRENT stored values, atomically
curl -s -X PATCH $BASE/invoices/$INV -H "$H" -d "{\"transaction_ids\": [\"$HOTEL\",\"$RAIL\"]}"

# delete the invoice (transactions are untouched and become editable again if no other invoice has them)
curl -i -X DELETE $BASE/invoices/$INV     # 204
```

## Behaviour worth knowing

- **Money** is `BigDecimal`, scale 2, `DECIMAL(19,2)`; never floating point. Amounts with more than 2 decimals are rejected (400), not rounded.
  `total` is the gross amount (it already includes that transaction's tax and fee lines); the service does not recompute it.
- **Duplicate identity** is `external_id`, enforced by a `UNIQUE` constraint. A duplicate is checked *before* deep validation, so a
  client retrying a booking always gets the stored id back (409), whatever the retry body looks like.
- **Same id twice on one invoice** => 409 `duplicate_transaction_id` (no silent dedupe, no double counting).
- **Tax rollup** groups by *(name, rate)*; **fee rollup** groups by *name*. Navan fees are transactions of their own, so they appear as invoice lines, not in the fee rollup.
- A transaction may appear on more than one invoice; it is locked (PATCH/DELETE => 409) while it is on any of them.
- Timestamps are ISO-8601 with an offset (stored as UTC).

## Layout

```
src/main/java/com/example/travel
  transaction/   controller, service, repository, DuplicateIdentityGuard, metadata/ (one validator per type)
  invoice/       controller, service, repository, InvoiceRollupCalculator (pure function)
  common/        Money rules, exceptions      web/  error mapping, pagination, health
src/main/resources/schema.sql   all tables, constraints and indexes
fixtures/task-b/                the provided fixtures
examples/                       the seed bodies as individual files (used by the curls)
```

See `ARCHITECTURE.md` for the data model, concurrency decisions and the High volume section.
