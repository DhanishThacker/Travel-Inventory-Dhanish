package com.example.travel.transaction;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.travel.common.Money;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Persistence for transactions + their tax/fee lines. Plain SQL, no business rules.
 * Transaction boundaries are owned by the service layer.
 */
@Repository
public class TransactionRepository {

    private static final String COLUMNS =
            "id, external_id, type, currency, occurred_at, total, metadata, created_at, updated_at";

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final ObjectMapper mapper;

    public TransactionRepository(JdbcTemplate jdbc, NamedParameterJdbcTemplate named, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.named = named;
        this.mapper = mapper;
    }

    // ------------------------------------------------------------------ writes

    /** Inserts header + tax lines + fee lines. Call inside one DB transaction. */
    public void insert(Transaction t) {
        jdbc.update("INSERT INTO transactions (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                t.id(), t.externalId(), t.type(), t.currency(), t.occurredAt(), t.total(),
                toJson(t.metadata()), t.createdAt(), t.updatedAt());
        insertTaxLines(t.id(), t.taxLines());
        insertFeeLines(t.id(), t.feeLines());
    }

    public void updateHeader(String id, OffsetDateTime occurredAt, BigDecimal total, JsonNode metadata,
                             OffsetDateTime updatedAt) {
        jdbc.update("UPDATE transactions SET occurred_at = ?, total = ?, metadata = ?, updated_at = ? WHERE id = ?",
                occurredAt, total, toJson(metadata), updatedAt, id);
    }

    public void replaceTaxLines(String id, List<TaxLine> lines) {
        jdbc.update("DELETE FROM transaction_tax_lines WHERE transaction_id = ?", id);
        insertTaxLines(id, lines);
    }

    public void replaceFeeLines(String id, List<FeeLine> lines) {
        jdbc.update("DELETE FROM transaction_fee_lines WHERE transaction_id = ?", id);
        insertFeeLines(id, lines);
    }

    /** Child tax/fee lines go with it (ON DELETE CASCADE). */
    public int delete(String id) {
        return jdbc.update("DELETE FROM transactions WHERE id = ?", id);
    }

    // ------------------------------------------------------------------ locking / checks

    /**
     * Takes a row lock on the transaction for the rest of the current DB transaction.
     * Returns false if the row does not exist. Serialises PATCH / DELETE / "add to invoice".
     */
    public boolean lockForUpdate(String id) {
        return !jdbc.queryForList("SELECT id FROM transactions WHERE id = ? FOR UPDATE", String.class, id).isEmpty();
    }

    /** True if the transaction is on at least one invoice (index lookup on invoice_lines.transaction_id). */
    public boolean isInvoiced(String id) {
        return !jdbc.queryForList(
                "SELECT 1 FROM invoice_lines WHERE transaction_id = ? LIMIT 1", Integer.class, id).isEmpty();
    }

    public Optional<String> findIdByExternalId(String externalId) {
        List<String> ids = jdbc.queryForList(
                "SELECT id FROM transactions WHERE external_id = ?", String.class, externalId);
        return ids.isEmpty() ? Optional.empty() : Optional.of(ids.get(0));
    }

    // ------------------------------------------------------------------ reads

    public Optional<Transaction> findById(String id) {
        List<Header> headers = jdbc.query(
                "SELECT " + COLUMNS + " FROM transactions WHERE id = ?", this::mapHeader, id);
        if (headers.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(assemble(headers).get(0));
    }

    public Map<String, Transaction> findAllByIds(Collection<String> ids) {
        Map<String, Transaction> out = new HashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        List<Header> headers = named.query(
                "SELECT " + COLUMNS + " FROM transactions WHERE id IN (:ids)", Map.of("ids", ids), this::mapHeader);
        for (Transaction t : assemble(headers)) {
            out.put(t.id(), t);
        }
        return out;
    }

    /** One bounded page, newest first. Always LIMIT/OFFSET in SQL, never paginated in memory. */
    public List<Transaction> list(int limit, int offset) {
        List<Header> headers = jdbc.query(
                "SELECT " + COLUMNS + " FROM transactions ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                this::mapHeader, limit, offset);
        return assemble(headers);
    }

    // ------------------------------------------------------------------ internals

    private record Header(String id, String externalId, String type, String currency, OffsetDateTime occurredAt,
                          BigDecimal total, String metadata, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    private Header mapHeader(ResultSet rs, int rowNum) throws SQLException {
        return new Header(
                rs.getString("id"),
                rs.getString("external_id"),
                rs.getString("type"),
                rs.getString("currency"),
                rs.getObject("occurred_at", OffsetDateTime.class),
                rs.getBigDecimal("total"),
                rs.getString("metadata"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    /** Loads tax and fee lines for ALL headers with one query each (no N+1). */
    private List<Transaction> assemble(List<Header> headers) {
        List<String> ids = new ArrayList<>(headers.size());
        for (Header h : headers) {
            ids.add(h.id());
        }
        Map<String, List<TaxLine>> taxes = loadTaxLines(ids);
        Map<String, List<FeeLine>> fees = loadFeeLines(ids);

        List<Transaction> out = new ArrayList<>(headers.size());
        for (Header h : headers) {
            out.add(new Transaction(
                    h.id(), h.type(), h.externalId(), h.currency(), h.occurredAt(), h.total(),
                    taxes.getOrDefault(h.id(), List.of()),
                    fees.getOrDefault(h.id(), List.of()),
                    fromJson(h.metadata()),
                    h.createdAt(), h.updatedAt()));
        }
        return out;
    }

    private Map<String, List<TaxLine>> loadTaxLines(List<String> ids) {
        Map<String, List<TaxLine>> out = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        named.query(
                "SELECT transaction_id, name, rate, amount FROM transaction_tax_lines "
                        + "WHERE transaction_id IN (:ids) ORDER BY transaction_id, line_no",
                Map.of("ids", ids),
                (RowCallbackHandler) rs -> out
                        .computeIfAbsent(rs.getString("transaction_id"), k -> new ArrayList<>())
                        .add(new TaxLine(
                                rs.getString("name"),
                                Money.displayRate(rs.getBigDecimal("rate")),
                                rs.getBigDecimal("amount"))));
        return out;
    }

    private Map<String, List<FeeLine>> loadFeeLines(List<String> ids) {
        Map<String, List<FeeLine>> out = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        named.query(
                "SELECT transaction_id, name, amount FROM transaction_fee_lines "
                        + "WHERE transaction_id IN (:ids) ORDER BY transaction_id, line_no",
                Map.of("ids", ids),
                (RowCallbackHandler) rs -> out
                        .computeIfAbsent(rs.getString("transaction_id"), k -> new ArrayList<>())
                        .add(new FeeLine(rs.getString("name"), rs.getBigDecimal("amount"))));
        return out;
    }

    private void insertTaxLines(String id, List<TaxLine> lines) {
        if (lines.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(
                "INSERT INTO transaction_tax_lines (transaction_id, line_no, name, rate, amount) VALUES (?, ?, ?, ?, ?)",
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int i) throws SQLException {
                        TaxLine line = lines.get(i);
                        ps.setString(1, id);
                        ps.setInt(2, i);
                        ps.setString(3, line.name());
                        if (line.rate() == null) {
                            ps.setNull(4, Types.DECIMAL);
                        } else {
                            ps.setBigDecimal(4, line.rate());
                        }
                        ps.setBigDecimal(5, line.amount());
                    }

                    @Override
                    public int getBatchSize() {
                        return lines.size();
                    }
                });
    }

    private void insertFeeLines(String id, List<FeeLine> lines) {
        if (lines.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(
                "INSERT INTO transaction_fee_lines (transaction_id, line_no, name, amount) VALUES (?, ?, ?, ?)",
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int i) throws SQLException {
                        FeeLine line = lines.get(i);
                        ps.setString(1, id);
                        ps.setInt(2, i);
                        ps.setString(3, line.name());
                        ps.setBigDecimal(4, line.amount());
                    }

                    @Override
                    public int getBatchSize() {
                        return lines.size();
                    }
                });
    }

    private String toJson(JsonNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise metadata", e);
        }
    }

    private JsonNode fromJson(String json) {
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored metadata is not valid JSON", e);
        }
    }
}
