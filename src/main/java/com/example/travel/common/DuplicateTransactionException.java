package com.example.travel.common;

/** A transaction with the same external_id already exists. Carries the id of the existing one. */
public class DuplicateTransactionException extends ConflictException {

    private final String existingId;

    public DuplicateTransactionException(String externalId, String existingId) {
        super("duplicate_external_id",
                "A transaction with external_id '" + externalId + "' already exists");
        this.existingId = existingId;
    }

    public String getExistingId() {
        return existingId;
    }
}
