package com.example.travel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionApiTest extends AbstractApiTest {

    // ------------------------------------------------------------ 1. create each type, GET round-trips metadata

    @Test
    void createsAllFiveSeedsAndGetReturnsTypeSpecificMetadata() {
        String flightId = createTxn("flight");
        String hotelId = createTxn("hotel");
        String railId = createTxn("rail");
        String tripFeeId = createTxn("trip_fee");
        String agentFeeId = createTxn("agent_call_fee");

        // flight: origin/destination, fare, coupons with flight number; tax and fee lines kept apart
        JsonNode flight = get("/transactions/" + flightId).body();
        assertThat(flight.get("type").asText()).isEqualTo("flight");
        assertThat(flight.get("external_id").asText()).isEqualTo("flight-TXL-LHR-2026-03-11");
        assertThat(flight.get("total").decimalValue()).isEqualByComparingTo("220.00");
        JsonNode flightMeta = flight.get("metadata");
        assertThat(flightMeta.get("origin").asText()).isEqualTo("TXL");
        assertThat(flightMeta.get("destination").asText()).isEqualTo("LHR");
        assertThat(flightMeta.get("fare").decimalValue()).isEqualByComparingTo("180.00");
        assertThat(flightMeta.get("coupons").size()).isEqualTo(1);
        assertThat(flightMeta.get("coupons").get(0).get("flight_number").asText()).isEqualTo("BA982");
        assertThat(flight.get("tax_lines").size()).isEqualTo(1);
        assertThat(flight.get("tax_lines").get(0).get("name").asText()).isEqualTo("Airport tax");
        assertThat(flight.get("fee_lines").size()).isEqualTo(1);
        assertThat(flight.get("fee_lines").get(0).get("name").asText()).isEqualTo("YQ");

        // hotel: property name + address
        JsonNode hotel = get("/transactions/" + hotelId).body();
        assertThat(hotel.get("type").asText()).isEqualTo("hotel");
        assertThat(hotel.get("total").decimalValue()).isEqualByComparingTo("331.70");
        JsonNode hotelMeta = hotel.get("metadata");
        assertThat(hotelMeta.get("name").asText()).isEqualTo("Hotel Mitte London");
        assertThat(hotelMeta.get("address").get("street").asText()).isEqualTo("12 Kingsway");
        assertThat(hotelMeta.get("address").get("city").asText()).isEqualTo("London");
        assertThat(hotelMeta.get("address").get("country").asText()).isEqualTo("GB");
        assertThat(hotelMeta.get("nights").asInt()).isEqualTo(2);
        assertThat(hotel.get("tax_lines").get(0).get("name").asText()).isEqualTo("VAT");
        assertThat(hotel.get("tax_lines").get(0).get("rate").decimalValue()).isEqualByComparingTo("0.07");
        assertThat(hotel.get("fee_lines").size()).isZero();

        // rail: two legs (a change of train), not just one OD pair
        JsonNode rail = get("/transactions/" + railId).body();
        assertThat(rail.get("type").asText()).isEqualTo("rail");
        JsonNode legs = rail.get("metadata").get("legs");
        assertThat(legs.size()).isEqualTo(2);
        assertThat(legs.get(0).get("destination").asText()).isEqualTo("Lille Europe");
        assertThat(legs.get(1).get("origin").asText()).isEqualTo("Lille Europe");
        assertThat(legs.get(1).get("train_number").asText()).isEqualTo("EST9122");
        assertThat(rail.get("fee_lines").get(0).get("name").asText()).isEqualTo("Booking fee");

        // navan_fee: both kinds
        JsonNode tripFee = get("/transactions/" + tripFeeId).body();
        assertThat(tripFee.get("type").asText()).isEqualTo("navan_fee");
        assertThat(tripFee.get("metadata").get("fee_kind").asText()).isEqualTo("trip_fee");
        assertThat(tripFee.get("metadata").get("related_external_id").asText()).isEqualTo("flight-TXL-LHR-2026-03-11");
        JsonNode agentFee = get("/transactions/" + agentFeeId).body();
        assertThat(agentFee.get("metadata").get("fee_kind").asText()).isEqualTo("agent_call_fee");
        assertThat(agentFee.get("total").decimalValue()).isEqualByComparingTo("15.00");

        assertThat(count("transactions")).isEqualTo(5);
    }

    @Test
    void createReturns201WithServerIdAndLocation() {
        Resp created = post("/transactions", seedJson("flight"));
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.text("id")).startsWith("txn_");
        assertThat(created.body().get("metadata").get("coupons").size()).isEqualTo(1);
    }

    @Test
    void rejectsInvalidInputAndStoresNothing() {
        ObjectNode unknownType = seed("flight");
        unknownType.put("type", "bus");
        unknownType.put("external_id", "bus-1");
        assertThat(post("/transactions", unknownType.toString()).status()).isEqualTo(400);

        ObjectNode unknownFeeKind = seed("trip_fee");
        ((ObjectNode) unknownFeeKind.get("metadata")).put("fee_kind", "gift_wrap");
        unknownFeeKind.put("external_id", "fee-bad-kind");
        assertThat(post("/transactions", unknownFeeKind.toString()).status()).isEqualTo(400);

        ObjectNode emptyMetadata = seed("flight");
        emptyMetadata.set("metadata", MAPPER.createObjectNode());
        emptyMetadata.put("external_id", "flight-empty-metadata");
        assertThat(post("/transactions", emptyMetadata.toString()).status()).isEqualTo(400);

        ObjectNode flightWithHotelMetadata = seed("flight");
        flightWithHotelMetadata.set("metadata", seed("hotel").get("metadata"));
        flightWithHotelMetadata.put("external_id", "flight-hotel-blob");
        assertThat(post("/transactions", flightWithHotelMetadata.toString()).status()).isEqualTo(400);

        ObjectNode railWithoutLegs = seed("rail");
        ((ObjectNode) railWithoutLegs.get("metadata")).putArray("legs");
        railWithoutLegs.put("external_id", "rail-no-legs");
        assertThat(post("/transactions", railWithoutLegs.toString()).status()).isEqualTo(400);

        ObjectNode missingTaxLines = seed("flight");
        missingTaxLines.remove("tax_lines");
        missingTaxLines.put("external_id", "flight-no-tax-lines");
        assertThat(post("/transactions", missingTaxLines.toString()).status()).isEqualTo(400);

        ObjectNode tooManyDecimals = seed("flight");
        tooManyDecimals.put("total", new java.math.BigDecimal("220.005"));
        tooManyDecimals.put("external_id", "flight-three-decimals");
        assertThat(post("/transactions", tooManyDecimals.toString()).status()).isEqualTo(400);

        assertThat(post("/transactions", "{ not json").status()).isEqualTo(400);
        assertThat(count("transactions")).isZero();
    }

    @Test
    void getUnknownTransactionIs404() {
        assertThat(get("/transactions/txn_does_not_exist").status()).isEqualTo(404);
    }

    // ------------------------------------------------------------ 2. duplicate external_id

    @Test
    void duplicateExternalIdDoesNotInsertSecondRowAndReturnsExistingId() {
        Resp first = post("/transactions", seedJson("flight"));
        assertThat(first.status()).isEqualTo(201);

        // the fixture's duplicate has the same external_id but a different body (empty coupons)
        Resp duplicate = post("/transactions", seedJson("duplicate_flight"));
        assertThat(duplicate.status()).isEqualTo(409);
        assertThat(duplicate.text("existing_id")).isEqualTo(first.text("id"));
        assertThat(duplicate.text("error")).isEqualTo("duplicate_external_id");

        // the exact same body again
        Resp sameBody = post("/transactions", seedJson("flight"));
        assertThat(sameBody.status()).isEqualTo(409);
        assertThat(sameBody.text("existing_id")).isEqualTo(first.text("id"));

        assertThat(count("transactions")).isEqualTo(1);
        assertThat(count("transaction_tax_lines")).isEqualTo(1);
        // the original was not overwritten by the duplicate body
        JsonNode stored = get("/transactions/" + first.text("id")).body();
        assertThat(stored.get("metadata").get("coupons").size()).isEqualTo(1);
    }

    // ------------------------------------------------------------ 6. concurrent duplicate create

    @Test
    void concurrentCreatesWithSameExternalIdLeaveExactlyOneRow() throws Exception {
        int threads = 8;
        String body = seedJson("flight");
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Resp>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                Callable<Resp> task = () -> {
                    ready.countDown();
                    go.await();
                    return post("/transactions", body);
                };
                futures.add(pool.submit(task));
            }
            ready.await();
            go.countDown();

            List<Resp> results = new ArrayList<>();
            for (Future<Resp> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }

            long created = results.stream().filter(r -> r.status() == 201).count();
            long conflicts = results.stream().filter(r -> r.status() == 409).count();
            assertThat(created).as("exactly one request wins").isEqualTo(1L);
            assertThat(conflicts).as("everyone else gets 409").isEqualTo(threads - 1L);

            Set<String> ids = new HashSet<>();
            for (Resp r : results) {
                ids.add(r.status() == 201 ? r.text("id") : r.text("existing_id"));
            }
            assertThat(ids).as("all responses point at the same stored transaction").hasSize(1);
            assertThat(count("transactions")).isEqualTo(1);
            assertThat(count("transaction_tax_lines")).isEqualTo(1);
            assertThat(count("transaction_fee_lines")).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------ PATCH

    @Test
    void patchUpdatesANonInvoicedTransaction() {
        String id = createTxn("flight");

        Resp patched = patch("/transactions/" + id,
                "{\"total\": 230.00, \"occurred_at\": \"2026-03-11T07:00:00Z\","
                        + " \"tax_lines\": [{\"name\":\"Airport tax\",\"amount\":18.00}],"
                        + " \"fee_lines\": []}");
        assertThat(patched.status()).isEqualTo(200);
        assertThat(patched.body().get("total").decimalValue()).isEqualByComparingTo("230.00");
        assertThat(patched.body().get("tax_lines").get(0).get("amount").decimalValue()).isEqualByComparingTo("18.00");
        assertThat(patched.body().get("fee_lines").size()).isZero();
        assertThat(patched.body().get("occurred_at").asText()).isEqualTo("2026-03-11T07:00:00Z");
        // untouched fields survive
        assertThat(patched.body().get("metadata").get("coupons").size()).isEqualTo(1);
        assertThat(count("transaction_fee_lines")).isZero();
    }

    @Test
    void patchRejectsBadInputAndImmutableFieldsAndUnknownIds() {
        String id = createTxn("flight");
        assertThat(patch("/transactions/" + id, "{\"type\": \"hotel\"}").status()).isEqualTo(400);
        assertThat(patch("/transactions/" + id, "{\"external_id\": \"other\"}").status()).isEqualTo(400);
        assertThat(patch("/transactions/" + id, "{}").status()).isEqualTo(400);
        assertThat(patch("/transactions/" + id, "{\"metadata\": {}}").status()).isEqualTo(400);
        assertThat(patch("/transactions/" + id, "{\"total\": 1.234}").status()).isEqualTo(400);
        assertThat(patch("/transactions/txn_missing", "{\"total\": 1.00}").status()).isEqualTo(404);

        JsonNode stored = get("/transactions/" + id).body();
        assertThat(stored.get("total").decimalValue()).isEqualByComparingTo("220.00");
        assertThat(stored.get("metadata").get("coupons").size()).isEqualTo(1);
    }

    // ------------------------------------------------------------ 7. PATCH after invoiced => 409

    @Test
    void patchAfterInvoicedReturns409AndNothingChanges() {
        String flightId = createTxn("flight");
        String invoiceId = createInvoice(flightId);

        Resp patched = patch("/transactions/" + flightId, "{\"total\": 999.00}");
        assertThat(patched.status()).isEqualTo(409);
        assertThat(patched.text("error")).isEqualTo("transaction_invoiced");

        assertThat(get("/transactions/" + flightId).body().get("total").decimalValue()).isEqualByComparingTo("220.00");
        JsonNode invoice = get("/invoices/" + invoiceId).body();
        assertThat(invoice.get("totals").get("grand_total").decimalValue()).isEqualByComparingTo("220.00");
        assertThat(invoice.get("lines").get(0).get("total").decimalValue()).isEqualByComparingTo("220.00");
    }

    // ------------------------------------------------------------ 8. DELETE

    @Test
    void deleteAfterInvoicedReturns409AndRowStillExists() {
        String flightId = createTxn("flight");
        createInvoice(flightId);

        assertThat(delete("/transactions/" + flightId).status()).isEqualTo(409);
        assertThat(get("/transactions/" + flightId).status()).isEqualTo(200);
        assertThat(count("transactions")).isEqualTo(1);
        assertThat(count("transaction_tax_lines")).isEqualTo(1);
    }

    @Test
    void deleteRemovesNonInvoicedTransactionAndItsLines() {
        String flightId = createTxn("flight");
        assertThat(count("transaction_tax_lines")).isEqualTo(1);
        assertThat(count("transaction_fee_lines")).isEqualTo(1);

        assertThat(delete("/transactions/" + flightId).status()).isEqualTo(204);
        assertThat(get("/transactions/" + flightId).status()).isEqualTo(404);
        assertThat(count("transactions")).isZero();
        assertThat(count("transaction_tax_lines")).isZero();
        assertThat(count("transaction_fee_lines")).isZero();
        assertThat(delete("/transactions/" + flightId).status()).isEqualTo(404);
    }

    // ------------------------------------------------------------ concurrent PATCH never tears the lists

    @Test
    void concurrentPatchesNeverTearTaxAndFeeLists() throws Exception {
        String id = createTxn("flight");
        int writers = 6;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch ready = new CountDownLatch(writers);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Resp>> futures = new ArrayList<>();
            for (int w = 0; w < writers; w++) {
                String body = linesPatch(w, w + 1); // writer w writes w+1 tax lines and w+1 fee lines
                Callable<Resp> task = () -> {
                    ready.countDown();
                    go.await();
                    return patch("/transactions/" + id, body);
                };
                futures.add(pool.submit(task));
            }
            ready.await();
            go.countDown();
            for (Future<Resp> future : futures) {
                assertThat(future.get(60, TimeUnit.SECONDS).status()).isEqualTo(200);
            }

            // Last write wins, but the whole write of ONE client: both lists from the same writer, none mixed.
            JsonNode stored = get("/transactions/" + id).body();
            JsonNode tax = stored.get("tax_lines");
            JsonNode fee = stored.get("fee_lines");
            String firstTaxName = tax.get(0).get("name").asText();            // "T<writer>-0"
            int writer = Integer.parseInt(firstTaxName.substring(1, firstTaxName.indexOf('-')));
            assertThat(tax.size()).isEqualTo(writer + 1);
            assertThat(fee.size()).isEqualTo(writer + 1);
            for (JsonNode line : tax) {
                assertThat(line.get("name").asText()).startsWith("T" + writer + "-");
            }
            for (JsonNode line : fee) {
                assertThat(line.get("name").asText()).startsWith("F" + writer + "-");
            }
            assertThat(count("transaction_tax_lines")).isEqualTo(writer + 1);
            assertThat(count("transaction_fee_lines")).isEqualTo(writer + 1);
        } finally {
            pool.shutdownNow();
        }
    }

    private static String linesPatch(int writer, int lineCount) {
        StringBuilder tax = new StringBuilder("[");
        StringBuilder fee = new StringBuilder("[");
        for (int k = 0; k < lineCount; k++) {
            if (k > 0) {
                tax.append(',');
                fee.append(',');
            }
            tax.append("{\"name\":\"T").append(writer).append('-').append(k).append("\",\"amount\":1.00}");
            fee.append("{\"name\":\"F").append(writer).append('-').append(k).append("\",\"amount\":2.00}");
        }
        return "{\"tax_lines\":" + tax + "],\"fee_lines\":" + fee + "]}";
    }

    // ------------------------------------------------------------ 10. pagination is bounded

    @Test
    void listIsPaginatedWithDefaultOf50AndRejectsHugeLimits() {
        createTxn("flight");
        createTxn("hotel");
        createTxn("rail");

        Resp defaults = get("/transactions");
        assertThat(defaults.status()).isEqualTo(200);
        assertThat(defaults.body().get("limit").asInt()).isEqualTo(50);
        assertThat(defaults.body().get("offset").asInt()).isZero();
        assertThat(defaults.body().get("items").size()).isEqualTo(3);
        assertThat(defaults.body().get("has_more").asBoolean()).isFalse();
        // list items carry the full structure
        assertThat(defaults.body().get("items").get(0).has("metadata")).isTrue();
        assertThat(defaults.body().get("items").get(0).has("tax_lines")).isTrue();

        assertThat(get("/transactions?limit=1000").status()).isEqualTo(400);
        assertThat(get("/transactions?limit=101").status()).isEqualTo(400);
        assertThat(get("/transactions?limit=0").status()).isEqualTo(400);
        assertThat(get("/transactions?offset=-1").status()).isEqualTo(400);
        assertThat(get("/transactions?limit=abc").status()).isEqualTo(400);
        assertThat(get("/transactions?limit=100").status()).isEqualTo(200);

        Resp firstPage = get("/transactions?limit=1");
        assertThat(firstPage.body().get("items").size()).isEqualTo(1);
        assertThat(firstPage.body().get("has_more").asBoolean()).isTrue();

        Resp lastPage = get("/transactions?limit=1&offset=2");
        assertThat(lastPage.body().get("items").size()).isEqualTo(1);
        assertThat(lastPage.body().get("has_more").asBoolean()).isFalse();

        // pages do not overlap
        Set<String> seen = new HashSet<>();
        for (int offset = 0; offset < 3; offset++) {
            seen.add(get("/transactions?limit=1&offset=" + offset).body().get("items").get(0).get("id").asText());
        }
        assertThat(seen).hasSize(3);
    }

    @Test
    void healthEndpointIsUp() {
        Resp health = get("/health");
        assertThat(health.status()).isEqualTo(200);
        assertThat(health.text("status")).isEqualTo("UP");
    }
}
