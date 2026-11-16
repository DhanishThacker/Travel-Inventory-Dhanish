package com.example.travel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InvoiceApiTest extends AbstractApiTest {

    private List<String> createTrip() {
        return List.of(createTxn("flight"), createTxn("hotel"), createTxn("rail"),
                createTxn("trip_fee"), createTxn("agent_call_fee"));
    }

    private static JsonNode findByName(JsonNode array, String name) {
        for (JsonNode element : array) {
            if (name.equals(element.get("name").asText())) {
                return element;
            }
        }
        return null;
    }

    // ------------------------------------------------------------ 3. trip invoice matches expected-invoice.json

    @Test
    void tripInvoiceHasLinesTotalsAndRollupsMatchingTheExpectedFixture() {
        List<String> ids = createTrip();

        Resp created = post("/invoices", invoiceBody(ids));
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.text("id")).startsWith("inv_");

        // GET returns the same thing POST did
        JsonNode invoice = get("/invoices/" + created.text("id")).body();
        assertThat(invoice.get("currency").asText()).isEqualTo(EXPECTED_INVOICE.get("currency").asText());
        assertThat(invoice.get("lines").size()).isGreaterThanOrEqualTo(EXPECTED_INVOICE.get("line_count_minimum").asInt());

        // totals
        JsonNode expectedTotals = EXPECTED_INVOICE.get("totals");
        assertThat(invoice.get("totals").get("grand_total").decimalValue())
                .isEqualByComparingTo(expectedTotals.get("grand_total").decimalValue());
        assertThat(invoice.get("totals").get("grand_total").decimalValue()).isEqualByComparingTo("685.70");
        assertThat(invoice.get("total").decimalValue()).isEqualByComparingTo("685.70");

        Map<String, BigDecimal> contribution = new HashMap<>();
        for (JsonNode line : invoice.get("lines")) {
            String type = line.get("type").asText();
            String key = type.equals("navan_fee") ? line.get("metadata").get("fee_kind").asText() : type;
            contribution.put(key, line.get("total").decimalValue());
        }
        for (String key : List.of("flight", "hotel", "rail", "trip_fee", "agent_call_fee")) {
            assertThat(contribution.get(key)).as("contribution of %s", key)
                    .isEqualByComparingTo(expectedTotals.get(key).decimalValue());
        }

        // tax rollup: Airport tax 15.00 (no rate) and VAT @ 0.07 = 21.70
        JsonNode taxRollup = invoice.get("tax_rollup");
        assertThat(taxRollup.size()).isEqualTo(2);
        for (JsonNode expected : EXPECTED_INVOICE.get("tax_rollup_example")) {
            JsonNode actual = findByName(taxRollup, expected.get("name").asText());
            assertThat((Object) actual).as("tax rollup has %s", expected.get("name")).isNotNull();
            assertThat(actual.get("amount").decimalValue()).isEqualByComparingTo(expected.get("amount").decimalValue());
            if (expected.has("rate")) {
                assertThat(actual.get("rate").decimalValue()).isEqualByComparingTo(expected.get("rate").decimalValue());
            } else {
                assertThat(actual.has("rate")).isFalse();
            }
        }

        // fee rollup: YQ 25.00 and Booking fee 4.00 (Navan fees are lines of their own, not fee_lines)
        JsonNode feeRollup = invoice.get("fee_rollup");
        assertThat(feeRollup.size()).isEqualTo(2);
        for (JsonNode expected : EXPECTED_INVOICE.get("fee_rollup_example")) {
            JsonNode actual = findByName(feeRollup, expected.get("name").asText());
            assertThat((Object) actual).as("fee rollup has %s", expected.get("name")).isNotNull();
            assertThat(actual.get("amount").decimalValue()).isEqualByComparingTo(expected.get("amount").decimalValue());
        }

        assertThat(invoice.get("totals").get("tax_total").decimalValue()).isEqualByComparingTo("36.70");
        assertThat(invoice.get("totals").get("fee_total").decimalValue()).isEqualByComparingTo("29.00");
        assertThat(invoice.get("totals").get("by_type").get("navan_fee").decimalValue()).isEqualByComparingTo("40.00");

        // metadata survives into the snapshot lines
        for (JsonNode line : invoice.get("lines")) {
            switch (line.get("type").asText()) {
                case "flight" -> assertThat(line.get("metadata").get("coupons").get(0).get("flight_number").asText())
                        .isEqualTo("BA982");
                case "hotel" -> assertThat(line.get("metadata").get("address").get("city").asText()).isEqualTo("London");
                case "rail" -> assertThat(line.get("metadata").get("legs").size()).isEqualTo(2);
                default -> assertThat(line.get("metadata").has("fee_kind")).isTrue();
            }
        }
    }

    @Test
    void singleTransactionInvoiceUsingTransactionId() {
        String flightId = createTxn("flight");
        Resp created = post("/invoices", "{\"transaction_id\": \"" + flightId + "\"}");
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body().get("lines").size()).isEqualTo(1);
        assertThat(created.body().get("totals").get("grand_total").decimalValue()).isEqualByComparingTo("220.00");
    }

    // ------------------------------------------------------------ 4. snapshot is frozen

    @Test
    void invoiceIsAFrozenSnapshotOfTheTransactionAtInvoiceTime() {
        String flightId = createTxn("flight");
        String invoiceId = createInvoice(flightId);

        // The API refuses to edit an invoiced transaction, so simulate "someone changed the source row"
        // directly in the database. The invoice must not move.
        jdbc.update("UPDATE transactions SET total = ? WHERE id = ?", new BigDecimal("999.00"), flightId);
        jdbc.update("UPDATE transaction_tax_lines SET amount = ? WHERE transaction_id = ?", new BigDecimal("99.00"), flightId);
        assertThat(get("/transactions/" + flightId).body().get("total").decimalValue()).isEqualByComparingTo("999.00");

        JsonNode invoice = get("/invoices/" + invoiceId).body();
        assertThat(invoice.get("lines").get(0).get("total").decimalValue()).isEqualByComparingTo("220.00");
        assertThat(invoice.get("lines").get(0).get("tax_lines").get(0).get("amount").decimalValue()).isEqualByComparingTo("15.00");
        assertThat(invoice.get("totals").get("grand_total").decimalValue()).isEqualByComparingTo("220.00");
        assertThat(invoice.get("tax_rollup").get(0).get("amount").decimalValue()).isEqualByComparingTo("15.00");

        // PATCH /invoices/{id} is the explicit way to re-snapshot: it rebuilds from CURRENT stored values.
        Resp rebuilt = patch("/invoices/" + invoiceId, invoiceBody(flightId));
        assertThat(rebuilt.status()).isEqualTo(200);
        assertThat(rebuilt.body().get("totals").get("grand_total").decimalValue()).isEqualByComparingTo("999.00");
        assertThat(rebuilt.text("id")).isEqualTo(invoiceId);
    }

    // ------------------------------------------------------------ 5. mixed currency

    @Test
    void mixedCurrencyInvoiceIsRejectedWith409() {
        String flightId = createTxn("flight"); // EUR
        ObjectNode usdHotel = seed("hotel");
        usdHotel.put("external_id", "hotel-LON-USD");
        usdHotel.put("currency", "USD");
        Resp usd = post("/transactions", usdHotel.toString());
        assertThat(usd.status()).isEqualTo(201);

        Resp invoice = post("/invoices", invoiceBody(flightId, usd.text("id")));
        assertThat(invoice.status()).isEqualTo(409);
        assertThat(invoice.text("error")).isEqualTo("mixed_currency");
        assertThat(count("invoices")).isZero();
        assertThat(count("invoice_lines")).isZero();
    }

    @Test
    void mixedCurrencyPatchIsRejectedAndLeavesTheInvoiceUntouched() {
        String flightId = createTxn("flight");
        String invoiceId = createInvoice(flightId);

        ObjectNode usdHotel = seed("hotel");
        usdHotel.put("external_id", "hotel-LON-USD");
        usdHotel.put("currency", "USD");
        String usdId = post("/transactions", usdHotel.toString()).text("id");

        assertThat(patch("/invoices/" + invoiceId, invoiceBody(flightId, usdId)).status()).isEqualTo(409);

        JsonNode unchanged = get("/invoices/" + invoiceId).body();
        assertThat(unchanged.get("lines").size()).isEqualTo(1);
        assertThat(unchanged.get("currency").asText()).isEqualTo("EUR");
        assertThat(unchanged.get("totals").get("grand_total").decimalValue()).isEqualByComparingTo("220.00");
        // the USD hotel is not on any invoice, so it is still editable
        assertThat(patch("/transactions/" + usdId, "{\"total\": 300.00}").status()).isEqualTo(200);
    }

    // ------------------------------------------------------------ request validation

    @Test
    void invoiceRequestValidation() {
        String flightId = createTxn("flight");

        // same id twice => 409, never double-counted
        Resp twice = post("/invoices", invoiceBody(flightId, flightId));
        assertThat(twice.status()).isEqualTo(409);
        assertThat(twice.text("error")).isEqualTo("duplicate_transaction_id");

        // unknown transaction => 404, nothing created
        assertThat(post("/invoices", invoiceBody(flightId, "txn_missing")).status()).isEqualTo(404);

        // shape errors => 400
        assertThat(post("/invoices", "{}").status()).isEqualTo(400);
        assertThat(post("/invoices", "{\"transaction_ids\": []}").status()).isEqualTo(400);
        assertThat(post("/invoices",
                "{\"transaction_id\": \"" + flightId + "\", \"transaction_ids\": [\"" + flightId + "\"]}").status())
                .isEqualTo(400);
        assertThat(post("/invoices", "not json").status()).isEqualTo(400);

        assertThat(count("invoices")).isZero();
    }

    // ------------------------------------------------------------ PATCH invoice replaces the set

    @Test
    void patchInvoiceReplacesLinesAndFreesRemovedTransactions() {
        String flightId = createTxn("flight");
        String hotelId = createTxn("hotel");
        String railId = createTxn("rail");
        String invoiceId = createInvoice(flightId);

        // flight is invoiced => locked
        assertThat(patch("/transactions/" + flightId, "{\"total\": 221.00}").status()).isEqualTo(409);

        Resp replaced = patch("/invoices/" + invoiceId, invoiceBody(hotelId, railId));
        assertThat(replaced.status()).isEqualTo(200);
        assertThat(replaced.body().get("lines").size()).isEqualTo(2);
        assertThat(replaced.body().get("totals").get("grand_total").decimalValue()).isEqualByComparingTo("425.70");
        assertThat(replaced.body().get("tax_rollup").size()).isEqualTo(1);               // only VAT now
        assertThat(replaced.body().get("fee_rollup").get(0).get("name").asText()).isEqualTo("Booking fee");
        assertThat(count("invoice_lines")).isEqualTo(2);
        assertThat(count("invoice_line_tax_lines")).isEqualTo(1);

        // flight left the invoice => editable again; hotel is now locked
        assertThat(patch("/transactions/" + flightId, "{\"total\": 221.00}").status()).isEqualTo(200);
        assertThat(patch("/transactions/" + hotelId, "{\"total\": 1.00}").status()).isEqualTo(409);

        assertThat(patch("/invoices/inv_missing", invoiceBody(flightId)).status()).isEqualTo(404);
    }

    // ------------------------------------------------------------ 9. delete invoice

    @Test
    void deleteInvoiceRemovesSnapshotButNeverTheTransactions() {
        List<String> ids = createTrip();
        String invoiceId = createInvoice(ids.toArray(new String[0]));
        assertThat(count("invoice_lines")).isEqualTo(5);

        assertThat(delete("/invoices/" + invoiceId).status()).isEqualTo(204);
        assertThat(get("/invoices/" + invoiceId).status()).isEqualTo(404);
        assertThat(delete("/invoices/" + invoiceId).status()).isEqualTo(404);

        assertThat(count("invoices")).isZero();
        assertThat(count("invoice_lines")).isZero();
        assertThat(count("invoice_line_tax_lines")).isZero();
        assertThat(count("invoice_line_fee_lines")).isZero();
        assertThat(count("invoice_tax_rollup")).isZero();
        assertThat(count("invoice_fee_rollup")).isZero();

        // all five transactions are intact and editable / deletable again
        assertThat(count("transactions")).isEqualTo(5);
        for (String id : ids) {
            assertThat(get("/transactions/" + id).status()).isEqualTo(200);
        }
        assertThat(patch("/transactions/" + ids.get(0), "{\"total\": 221.00}").status()).isEqualTo(200);
        assertThat(delete("/transactions/" + ids.get(0)).status()).isEqualTo(204);
    }

    // ------------------------------------------------------------ pagination for invoices

    @Test
    void invoiceListIsPaginatedAndReturnsSummaries() {
        String flightId = createTxn("flight");
        String hotelId = createTxn("hotel");
        createInvoice(flightId);
        createInvoice(hotelId);

        Resp list = get("/invoices");
        assertThat(list.status()).isEqualTo(200);
        assertThat(list.body().get("limit").asInt()).isEqualTo(50);
        assertThat(list.body().get("items").size()).isEqualTo(2);
        assertThat(list.body().get("items").get(0).get("line_count").asInt()).isEqualTo(1);
        assertThat(list.body().get("items").get(0).has("lines")).isFalse();

        assertThat(get("/invoices?limit=1").body().get("has_more").asBoolean()).isTrue();
        assertThat(get("/invoices?limit=101").status()).isEqualTo(400);
        assertThat(get("/invoices?offset=-5").status()).isEqualTo(400);
    }

    // ------------------------------------------------------------ edit vs invoice race

    /**
     * PATCH and "add to invoice" both take the transaction's row lock, so exactly one order happens:
     * either the PATCH commits first (200) and the invoice snapshots the NEW total, or the invoice wins
     * and the PATCH is refused (409) and the invoice keeps the OLD total. Never a stale or torn mix.
     */
    @Test
    void raceBetweenPatchAndInvoiceCreateIsSerialised() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 5; round++) {
                ObjectNode flight = seed("flight");
                flight.put("external_id", "race-flight-" + round);
                String id = post("/transactions", flight.toString()).text("id");

                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch go = new CountDownLatch(1);
                Callable<Resp> doPatch = () -> {
                    ready.countDown();
                    go.await();
                    return patch("/transactions/" + id, "{\"total\": 999.00}");
                };
                Callable<Resp> doInvoice = () -> {
                    ready.countDown();
                    go.await();
                    return post("/invoices", invoiceBody(id));
                };
                Future<Resp> patchResult = pool.submit(doPatch);
                Future<Resp> invoiceResult = pool.submit(doInvoice);
                ready.await();
                go.countDown();

                Resp patched = patchResult.get(60, TimeUnit.SECONDS);
                Resp invoiced = invoiceResult.get(60, TimeUnit.SECONDS);

                assertThat(invoiced.status()).as("round %d invoice", round).isEqualTo(201);
                BigDecimal invoiceLineTotal = invoiced.body().get("lines").get(0).get("total").decimalValue();
                BigDecimal storedTotal = get("/transactions/" + id).body().get("total").decimalValue();

                // the invoice always shows exactly what is stored (it can never be stale)
                assertThat(invoiceLineTotal).as("round %d", round).isEqualByComparingTo(storedTotal);
                if (patched.status() == 200) {
                    assertThat(invoiceLineTotal).isEqualByComparingTo("999.00");
                } else {
                    assertThat(patched.status()).as("round %d patch", round).isEqualTo(409);
                    assertThat(invoiceLineTotal).isEqualByComparingTo("220.00");
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
