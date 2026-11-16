package com.example.travel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the real application on a random port against a REAL file-backed H2 database in a temp
 * directory (never jdbc:h2:mem), and talks to it over real HTTP with the JDK HttpClient
 * (which, unlike HttpURLConnection, supports PATCH).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class AbstractApiTest {

    private static final Path DB_DIR;

    static {
        try {
            DB_DIR = Files.createTempDirectory("travel-inventory-test-db");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:file:" + DB_DIR.resolve("testdb").toAbsolutePath() + ";LOCK_TIMEOUT=10000");
    }

    protected static final ObjectMapper MAPPER = new ObjectMapper();
    protected static final JsonNode SEEDS = readFixture("fixtures/task-b/seed-payloads.json");
    protected static final JsonNode EXPECTED_INVOICE = readFixture("fixtures/task-b/expected-invoice.json");

    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    /** Invoices first (cascades to snapshot tables), then transactions (cascades to tax/fee lines). */
    @BeforeEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM invoices");
        jdbc.update("DELETE FROM transactions");
    }

    // ------------------------------------------------------------------ HTTP helpers

    record Resp(int status, JsonNode body) {
        String text(String field) {
            return body.get(field).asText();
        }
    }

    Resp send(String method, String path, String body) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json");
            request.method(method, body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body));
            HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
            String raw = response.body();
            return new Resp(response.statusCode(), raw == null || raw.isBlank() ? null : MAPPER.readTree(raw));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    Resp get(String path) {
        return send("GET", path, null);
    }

    Resp post(String path, String body) {
        return send("POST", path, body);
    }

    Resp patch(String path, String body) {
        return send("PATCH", path, body);
    }

    Resp delete(String path) {
        return send("DELETE", path, null);
    }

    // ------------------------------------------------------------------ fixture helpers

    private static JsonNode readFixture(String relativePath) {
        try {
            return MAPPER.readTree(Files.readString(Path.of(relativePath)));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + relativePath + " (run tests from the project root)", e);
        }
    }

    /** Fresh, mutable copy of one seed payload ("flight", "hotel", "rail", "trip_fee", ...). */
    ObjectNode seed(String name) {
        return SEEDS.get(name).deepCopy();
    }

    String seedJson(String name) {
        return SEEDS.get(name).toString();
    }

    /** POSTs a seed and returns the new transaction id (asserting 201). */
    String createTxn(String seedName) {
        Resp response = post("/transactions", seedJson(seedName));
        assertThat(response.status()).as("create %s: %s", seedName, response.body()).isEqualTo(201);
        return response.text("id");
    }

    /** POSTs an invoice for the given ids and returns the invoice id (asserting 201). */
    String createInvoice(String... transactionIds) {
        Resp response = post("/invoices", invoiceBody(transactionIds));
        assertThat(response.status()).as("create invoice: %s", response.body()).isEqualTo(201);
        return response.text("id");
    }

    String invoiceBody(String... transactionIds) {
        ObjectNode body = MAPPER.createObjectNode();
        ArrayNode ids = body.putArray("transaction_ids");
        for (String id : transactionIds) {
            ids.add(id);
        }
        return body.toString();
    }

    String invoiceBody(List<String> transactionIds) {
        return invoiceBody(transactionIds.toArray(new String[0]));
    }

    int count(String table) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }
}
