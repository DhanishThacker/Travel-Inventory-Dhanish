package com.example.travel.transaction;

import com.example.travel.common.BadRequestException;
import com.example.travel.common.ConflictException;
import com.example.travel.common.Money;
import com.example.travel.common.NotFoundException;
import com.example.travel.transaction.metadata.MetadataValidators;
import com.example.travel.web.Page;
import com.example.travel.web.PageParams;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Transaction use-cases. Owns the DB transaction boundaries. */
@Service
public class TransactionService {

    private final TransactionRepository repository;
    private final DuplicateIdentityGuard duplicateGuard;
    private final MetadataValidators metadataValidators;
    private final TransactionTemplate inTransaction;

    public TransactionService(TransactionRepository repository,
                              DuplicateIdentityGuard duplicateGuard,
                              MetadataValidators metadataValidators,
                              PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.duplicateGuard = duplicateGuard;
        this.metadataValidators = metadataValidators;
        this.inTransaction = new TransactionTemplate(transactionManager);
    }

    /**
     * Deliberately NOT @Transactional: the duplicate guard has to observe the commit/rollback of the
     * insert transaction (see {@link DuplicateIdentityGuard}).
     *
     * <p>Order of checks: unknown type (400) -> duplicate external_id (409) -> metadata/money
     * validation (400) -> atomic insert. Duplicate is checked before the deep validation so that a
     * client retrying a booking always gets the stored transaction's id back, whatever the retry body says.
     */
    public Transaction create(CreateTransactionRequest request) {
        TransactionType type = TransactionType.fromWire(request.type()).orElseThrow(() ->
                new BadRequestException("Unknown transaction type '" + request.type()
                        + "'. Expected one of: flight, hotel, rail, navan_fee"));
        return duplicateGuard.register(request.externalId(), () -> persist(type, request));
    }

    private Transaction persist(TransactionType type, CreateTransactionRequest request) {
        metadataValidators.validate(type, request.metadata());
        BigDecimal total = Money.amount(request.total(), "total");
        List<TaxLine> taxLines = TransactionLines.tax(request.taxLines(), "tax_lines");
        List<FeeLine> feeLines = TransactionLines.fee(request.feeLines(), "fee_lines");

        String id = "txn_" + UUID.randomUUID().toString().replace("-", "");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Transaction toStore = new Transaction(id, type.wire(), request.externalId(), request.currency(),
                request.occurredAt(), total, taxLines, feeLines, request.metadata(), now, now);

        // Header + tax lines + fee lines + metadata: all or nothing.
        return inTransaction.execute(status -> {
            repository.insert(toStore);
            return repository.findById(id).orElseThrow();
        });
    }

    public Transaction get(String id) {
        return repository.findById(id).orElseThrow(() -> notFound(id));
    }

    public Page<Transaction> list(PageParams params) {
        List<Transaction> rows = repository.list(params.limit() + 1, params.offset()); // +1 => has_more, no COUNT(*)
        boolean hasMore = rows.size() > params.limit();
        return new Page<>(hasMore ? rows.subList(0, params.limit()) : rows, params.limit(), params.offset(), hasMore);
    }

    /**
     * Lock the row, then check invoices, then write. Both "add to invoice" and "patch" take the same row lock,
     * so a transaction can never be edited and invoiced at the same time.
     */
    @Transactional
    public Transaction patch(String id, PatchTransactionRequest request) {
        if (!request.hasChanges()) {
            throw new BadRequestException(
                    "Nothing to update. Provide at least one of: occurred_at, total, tax_lines, fee_lines, metadata");
        }
        BigDecimal total = request.total() == null ? null : Money.amount(request.total(), "total");
        List<TaxLine> taxLines = request.taxLines() == null ? null : TransactionLines.tax(request.taxLines(), "tax_lines");
        List<FeeLine> feeLines = request.feeLines() == null ? null : TransactionLines.fee(request.feeLines(), "fee_lines");

        if (!repository.lockForUpdate(id)) {
            throw notFound(id);
        }
        if (repository.isInvoiced(id)) {
            throw invoiced(id, "modified");
        }

        Transaction current = repository.findById(id).orElseThrow(() -> notFound(id));
        JsonNode metadata = current.metadata();
        if (request.hasMetadata()) {
            TransactionType type = TransactionType.fromWire(current.type()).orElseThrow();
            metadataValidators.validate(type, request.metadata());
            metadata = request.metadata();
        }

        repository.updateHeader(id,
                request.occurredAt() != null ? request.occurredAt() : current.occurredAt(),
                total != null ? total : current.total(),
                metadata,
                OffsetDateTime.now(ZoneOffset.UTC));
        if (taxLines != null) {
            repository.replaceTaxLines(id, taxLines);
        }
        if (feeLines != null) {
            repository.replaceFeeLines(id, feeLines);
        }
        return repository.findById(id).orElseThrow(() -> notFound(id));
    }

    @Transactional
    public void delete(String id) {
        if (!repository.lockForUpdate(id)) {
            throw notFound(id);
        }
        if (repository.isInvoiced(id)) {
            throw invoiced(id, "deleted");
        }
        try {
            repository.delete(id);
        } catch (DataIntegrityViolationException e) {
            // Backstop: invoice_lines.transaction_id has a RESTRICT foreign key.
            throw invoiced(id, "deleted");
        }
    }

    private static NotFoundException notFound(String id) {
        return new NotFoundException("Transaction " + id + " not found");
    }

    private static ConflictException invoiced(String id, String verb) {
        return new ConflictException("transaction_invoiced",
                "Transaction " + id + " is on an invoice and cannot be " + verb);
    }
}
