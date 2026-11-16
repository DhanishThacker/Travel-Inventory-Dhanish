package com.example.travel.invoice;

import com.example.travel.common.Money;
import com.example.travel.transaction.FeeLine;
import com.example.travel.transaction.TaxLine;
import com.example.travel.transaction.Transaction;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

/**
 * Persistence for invoices and their frozen snapshot. Reads ONLY from invoice_* tables: an invoice is
 * never a live join over `transactions`.
 */
@Repository
public class InvoiceRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public InvoiceRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    // ------------------------------------------------------------------ writes

    public void insertHeader(String id, String currency, BigDecimal total, BigDecimal taxTotal, BigDecimal feeTotal,
                             int lineCount, OffsetDateTime now) {
        jdbc.update("INSERT INTO invoices (id, currency, total, tax_total, fee_total, line_count, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                id, currency, total, taxTotal, feeTotal, lineCount, now, now);
    }

    public void updateHeader(String id, String currency, BigDecimal total, BigDecimal taxTotal, BigDecimal feeTotal,
                             int lineCount, OffsetDateTime now) {
        jdbc.update("UPDATE invoices SET currency = ?, total = ?, tax_total = ?, fee_total = ?, line_count = ?, "
                        + "updated_at = ? WHERE id = ?",
                currency, total, taxTotal, feeTotal, lineCount, now, id);
    }

    /** Removes lines (and, by cascade, their tax/fee copies) and rollups. The invoice header stays. */
    public void clearSnapshot(String invoiceId) {
        jdbc.update("DELETE FROM invoice_lines WHERE invoice_id = ?", invoiceId);
        jdbc.update("DELETE FROM invoice_tax_rollup WHERE invoice_id = ?", invoiceId);
        jdbc.update("DELETE FROM invoice_fee_rollup WHERE invoice_id = ?", invoiceId);
    }

    /** Copies one transaction (header values, metadata, tax lines, fee lines) into the snapshot. */
    public void insertLine(String invoiceId, int lineNo, Transaction t) {
        jdbc.update("INSERT INTO invoice_lines (invoice_id, transaction_id, line_no, external_id, type, currency, "
                        + "occurred_at, total, metadata) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                invoiceId, t.id(), lineNo, t.externalId(), t.type(), t.currency(), t.occurredAt(), t.total(),
                toJson(t.metadata()));

        if (!t.taxLines().isEmpty()) {
            jdbc.batchUpdate("INSERT INTO invoice_line_tax_lines (invoice_id, transaction_id, line_no, name, rate, amount) "
                    + "VALUES (?, ?, ?, ?, ?, ?)", new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement ps, int i) throws SQLException {
                    TaxLine line = t.taxLines().get(i);
                    ps.setString(1, invoiceId);
                    ps.setString(2, t.id());
                    ps.setInt(3, i);
                    ps.setString(4, line.name());
                    setNullableDecimal(ps, 5, line.rate());
                    ps.setBigDecimal(6, line.amount());
                }

                @Override
                public int getBatchSize() {
                    return t.taxLines().size();
                }
            });
        }
        if (!t.feeLines().isEmpty()) {
            jdbc.batchUpdate("INSERT INTO invoice_line_fee_lines (invoice_id, transaction_id, line_no, name, amount) "
                    + "VALUES (?, ?, ?, ?, ?)", new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement ps, int i) throws SQLException {
                    FeeLine line = t.feeLines().get(i);
                    ps.setString(1, invoiceId);
                    ps.setString(2, t.id());
                    ps.setInt(3, i);
                    ps.setString(4, line.name());
                    ps.setBigDecimal(5, line.amount());
                }

                @Override
                public int getBatchSize() {
                    return t.feeLines().size();
                }
            });
        }
    }

    public void insertRollups(String invoiceId, List<TaxLine> taxRollup, List<FeeLine> feeRollup) {
        if (!taxRollup.isEmpty()) {
            jdbc.batchUpdate("INSERT INTO invoice_tax_rollup (invoice_id, line_no, name, rate, amount) "
                    + "VALUES (?, ?, ?, ?, ?)", new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement ps, int i) throws SQLException {
                    TaxLine line = taxRollup.get(i);
                    ps.setString(1, invoiceId);
                    ps.setInt(2, i);
                    ps.setString(3, line.name());
                    setNullableDecimal(ps, 4, line.rate());
                    ps.setBigDecimal(5, line.amount());
                }

                @Override
                public int getBatchSize() {
                    return taxRollup.size();
                }
            });
        }
        if (!feeRollup.isEmpty()) {
            jdbc.batchUpdate("INSERT INTO invoice_fee_rollup (invoice_id, line_no, name, amount) VALUES (?, ?, ?, ?)",
                    new BatchPreparedStatementSetter() {
                        @Override
                        public void setValues(PreparedStatement ps, int i) throws SQLException {
                            FeeLine line = feeRollup.get(i);
                            ps.setString(1, invoiceId);
                            ps.setInt(2, i);
                            ps.setString(3, line.name());
                            ps.setBigDecimal(4, line.amount());
                        }

                        @Override
                        public int getBatchSize() {
                            return feeRollup.size();
                        }
                    });
        }
    }

    /** Deletes the invoice; every snapshot table cascades. Underlying transactions are untouched. */
    public int delete(String id) {
        return jdbc.update("DELETE FROM invoices WHERE id = ?", id);
    }

    public boolean lockForUpdate(String id) {
        return !jdbc.queryForList("SELECT id FROM invoices WHERE id = ? FOR UPDATE", String.class, id).isEmpty();
    }

    // ------------------------------------------------------------------ reads

    private record HeaderRow(String id, String currency, BigDecimal total, BigDecimal taxTotal, BigDecimal feeTotal,
                             OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    private record LineRow(String transactionId, String externalId, String type, String currency,
                           OffsetDateTime occurredAt, BigDecimal total, String metadata) {
    }

    public Optional<Invoice> findById(String id) {
        List<HeaderRow> headers = jdbc.query(
                "SELECT id, currency, total, tax_total, fee_total, created_at, updated_at FROM invoices WHERE id = ?",
                (rs, n) -> new HeaderRow(
                        rs.getString("id"),
                        rs.getString("currency"),
                        rs.getBigDecimal("total"),
                        rs.getBigDecimal("tax_total"),
                        rs.getBigDecimal("fee_total"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("updated_at", OffsetDateTime.class)),
                id);
        if (headers.isEmpty()) {
            return Optional.empty();
        }
        HeaderRow header = headers.get(0);

        List<LineRow> lineRows = jdbc.query(
                "SELECT transaction_id, external_id, type, currency, occurred_at, total, metadata "
                        + "FROM invoice_lines WHERE invoice_id = ? ORDER BY line_no",
                (rs, n) -> new LineRow(
                        rs.getString("transaction_id"),
                        rs.getString("external_id"),
                        rs.getString("type"),
                        rs.getString("currency"),
                        rs.getObject("occurred_at", OffsetDateTime.class),
                        rs.getBigDecimal("total"),
                        rs.getString("metadata")),
                id);

        Map<String, List<TaxLine>> lineTaxes = new HashMap<>();
        jdbc.query("SELECT transaction_id, name, rate, amount FROM invoice_line_tax_lines "
                        + "WHERE invoice_id = ? ORDER BY transaction_id, line_no",
                (RowCallbackHandler) rs -> lineTaxes
                        .computeIfAbsent(rs.getString("transaction_id"), k -> new ArrayList<>())
                        .add(new TaxLine(rs.getString("name"),
                                Money.displayRate(rs.getBigDecimal("rate")),
                                rs.getBigDecimal("amount"))),
                id);

        Map<String, List<FeeLine>> lineFees = new HashMap<>();
        jdbc.query("SELECT transaction_id, name, amount FROM invoice_line_fee_lines "
                        + "WHERE invoice_id = ? ORDER BY transaction_id, line_no",
                (RowCallbackHandler) rs -> lineFees
                        .computeIfAbsent(rs.getString("transaction_id"), k -> new ArrayList<>())
                        .add(new FeeLine(rs.getString("name"), rs.getBigDecimal("amount"))),
                id);

        List<InvoiceLine> lines = new ArrayList<>(lineRows.size());
        Map<String, BigDecimal> byType = new LinkedHashMap<>();
        for (LineRow row : lineRows) {
            lines.add(new InvoiceLine(
                    row.transactionId(), row.externalId(), row.type(), row.currency(), row.occurredAt(), row.total(),
                    lineTaxes.getOrDefault(row.transactionId(), List.of()),
                    lineFees.getOrDefault(row.transactionId(), List.of()),
                    fromJson(row.metadata())));
            byType.merge(row.type(), row.total(), BigDecimal::add);
        }

        List<TaxLine> taxRollup = jdbc.query(
                "SELECT name, rate, amount FROM invoice_tax_rollup WHERE invoice_id = ? ORDER BY line_no",
                (rs, n) -> new TaxLine(rs.getString("name"),
                        Money.displayRate(rs.getBigDecimal("rate")),
                        rs.getBigDecimal("amount")),
                id);
        List<FeeLine> feeRollup = jdbc.query(
                "SELECT name, amount FROM invoice_fee_rollup WHERE invoice_id = ? ORDER BY line_no",
                (rs, n) -> new FeeLine(rs.getString("name"), rs.getBigDecimal("amount")),
                id);

        InvoiceTotals totals = new InvoiceTotals(header.total(), header.taxTotal(), header.feeTotal(), byType);
        return Optional.of(new Invoice(header.id(), header.currency(), header.total(),
                header.createdAt(), header.updatedAt(), lines, taxRollup, feeRollup, totals));
    }

    /** One bounded page of invoice headers, newest first. */
    public List<InvoiceSummary> list(int limit, int offset) {
        return jdbc.query(
                "SELECT id, currency, total, line_count, created_at, updated_at FROM invoices "
                        + "ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                (rs, n) -> new InvoiceSummary(
                        rs.getString("id"),
                        rs.getString("currency"),
                        rs.getBigDecimal("total"),
                        rs.getInt("line_count"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("updated_at", OffsetDateTime.class)),
                limit, offset);
    }

    // ------------------------------------------------------------------ internals

    private static void setNullableDecimal(PreparedStatement ps, int index, BigDecimal value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.DECIMAL);
        } else {
            ps.setBigDecimal(index, value);
        }
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
