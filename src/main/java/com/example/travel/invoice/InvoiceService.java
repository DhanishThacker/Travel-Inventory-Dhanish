package com.example.travel.invoice;

import com.example.travel.common.BadRequestException;
import com.example.travel.common.ConflictException;
import com.example.travel.common.NotFoundException;
import com.example.travel.transaction.Transaction;
import com.example.travel.transaction.TransactionRepository;
import com.example.travel.web.Page;
import com.example.travel.web.PageParams;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Invoice use-cases. Every write is ONE database transaction:
 * lock the source transactions (sorted order, so two writers can never deadlock), read their CURRENT
 * values, copy them into the snapshot tables, compute rollups.
 */
@Service
public class InvoiceService {

    static final int MAX_TRANSACTIONS_PER_INVOICE = 100;

    private final InvoiceRepository invoices;
    private final TransactionRepository transactions;

    public InvoiceService(InvoiceRepository invoices, TransactionRepository transactions) {
        this.invoices = invoices;
        this.transactions = transactions;
    }

    @Transactional
    public Invoice create(InvoiceRequest request) {
        List<String> ids = resolveIds(request);
        List<Transaction> sources = lockAndLoad(ids);

        String invoiceId = "inv_" + UUID.randomUUID().toString().replace("-", "");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        writeSnapshot(invoiceId, sources, now, true);
        return invoices.findById(invoiceId).orElseThrow();
    }

    /** Replaces the set of transactions and rebuilds the snapshot from CURRENT stored values, atomically. */
    @Transactional
    public Invoice replace(String invoiceId, InvoiceRequest request) {
        List<String> ids = resolveIds(request);
        if (!invoices.lockForUpdate(invoiceId)) {
            throw notFound(invoiceId);
        }
        List<Transaction> sources = lockAndLoad(ids);
        writeSnapshot(invoiceId, sources, OffsetDateTime.now(ZoneOffset.UTC), false);
        return invoices.findById(invoiceId).orElseThrow();
    }

    public Invoice get(String id) {
        return invoices.findById(id).orElseThrow(() -> notFound(id));
    }

    public Page<InvoiceSummary> list(PageParams params) {
        List<InvoiceSummary> rows = invoices.list(params.limit() + 1, params.offset());
        boolean hasMore = rows.size() > params.limit();
        return new Page<>(hasMore ? rows.subList(0, params.limit()) : rows, params.limit(), params.offset(), hasMore);
    }

    /** Deletes the invoice and its snapshot lines only. Transactions are never touched. */
    @Transactional
    public void delete(String id) {
        if (invoices.delete(id) == 0) {
            throw notFound(id);
        }
    }

    // ------------------------------------------------------------------ internals

    private List<String> resolveIds(InvoiceRequest request) {
        boolean hasSingle = request.transactionId() != null;
        boolean hasMany = request.transactionIds() != null;
        if (hasSingle == hasMany) {
            throw new BadRequestException("Provide exactly one of transaction_id or transaction_ids");
        }
        List<String> ids = hasSingle ? List.of(request.transactionId()) : request.transactionIds();
        if (ids.isEmpty()) {
            throw new BadRequestException("transaction_ids must not be empty");
        }
        if (ids.size() > MAX_TRANSACTIONS_PER_INVOICE) {
            throw new BadRequestException("An invoice can include at most " + MAX_TRANSACTIONS_PER_INVOICE + " transactions");
        }
        Set<String> seen = new HashSet<>();
        for (String id : ids) {
            if (id == null || id.isBlank()) {
                throw new BadRequestException("transaction ids must be non-empty strings");
            }
            if (!seen.add(id)) {
                // Chosen behaviour: reject, never silently double-count (and never silently dedupe).
                throw new ConflictException("duplicate_transaction_id",
                        "Transaction " + id + " is listed more than once");
            }
        }
        return ids;
    }

    /**
     * Row-locks every source transaction (ascending id order) and then loads their latest committed values.
     * Locking first means a concurrent PATCH either finished before we read, or waits until we commit
     * (and then sees the transaction is invoiced and fails with 409).
     */
    private List<Transaction> lockAndLoad(List<String> ids) {
        List<String> missing = new ArrayList<>();
        for (String id : new TreeSet<>(ids)) {
            if (!transactions.lockForUpdate(id)) {
                missing.add(id);
            }
        }
        if (!missing.isEmpty()) {
            throw new NotFoundException("Transactions not found: " + missing);
        }
        Map<String, Transaction> byId = transactions.findAllByIds(ids);
        List<Transaction> ordered = new ArrayList<>(ids.size());
        for (String id : ids) {
            ordered.add(byId.get(id));
        }
        return ordered;
    }

    private void writeSnapshot(String invoiceId, List<Transaction> sources, OffsetDateTime now, boolean isNew) {
        String currency = singleCurrency(sources);
        InvoiceRollupCalculator.Rollup rollup = InvoiceRollupCalculator.compute(sources);

        if (isNew) {
            invoices.insertHeader(invoiceId, currency, rollup.total(), rollup.taxTotal(), rollup.feeTotal(),
                    sources.size(), now);
        } else {
            invoices.clearSnapshot(invoiceId);
            invoices.updateHeader(invoiceId, currency, rollup.total(), rollup.taxTotal(), rollup.feeTotal(),
                    sources.size(), now);
        }
        for (int i = 0; i < sources.size(); i++) {
            invoices.insertLine(invoiceId, i, sources.get(i));
        }
        invoices.insertRollups(invoiceId, rollup.taxRollup(), rollup.feeRollup());
    }

    private static String singleCurrency(List<Transaction> sources) {
        Set<String> currencies = new TreeSet<>();
        for (Transaction t : sources) {
            currencies.add(t.currency());
        }
        if (currencies.size() > 1) {
            throw new ConflictException("mixed_currency",
                    "An invoice cannot mix currencies: " + Collections.unmodifiableSet(currencies));
        }
        return currencies.iterator().next();
    }

    private static NotFoundException notFound(String id) {
        return new NotFoundException("Invoice " + id + " not found");
    }
}
