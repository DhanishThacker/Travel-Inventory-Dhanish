package com.example.travel.transaction;

import com.example.travel.common.DuplicateTransactionException;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * Owns "what counts as the same transaction": the same {@code external_id}.
 *
 * <p>Two layers, on purpose:
 * <ol>
 *   <li>A cheap pre-check, so the common retry case never touches the write path.</li>
 *   <li>The real guarantee: the UNIQUE constraint on {@code transactions.external_id}. If two requests
 *       race past the pre-check, the database lets exactly one INSERT win; the loser gets a
 *       constraint violation, which is translated here into the same "duplicate" outcome with the
 *       winner's id.</li>
 * </ol>
 *
 * <p>The {@code insert} supplier must run (and commit or roll back) its own DB transaction. This class
 * must NOT be called from inside an outer transaction, otherwise the post-failure lookup could run in
 * an already-aborted transaction.
 */
@Component
public class DuplicateIdentityGuard {

    private final TransactionRepository repository;

    public DuplicateIdentityGuard(TransactionRepository repository) {
        this.repository = repository;
    }

    public <T> T register(String externalId, Supplier<T> insert) {
        Optional<String> existing = repository.findIdByExternalId(externalId);
        if (existing.isPresent()) {
            throw new DuplicateTransactionException(externalId, existing.get());
        }
        try {
            return insert.get();
        } catch (DataIntegrityViolationException e) {
            // Lost a race with a concurrent create (or some other integrity problem).
            Optional<String> winner = repository.findIdByExternalId(externalId);
            if (winner.isPresent()) {
                throw new DuplicateTransactionException(externalId, winner.get());
            }
            throw e;
        }
    }
}
