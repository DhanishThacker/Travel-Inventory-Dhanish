package com.example.travel.transaction.metadata;

import com.example.travel.transaction.TransactionType;
import com.fasterxml.jackson.databind.JsonNode;

/** Validates the type-specific {@code metadata} object of one transaction type. */
public interface MetadataValidator {

    TransactionType type();

    /** @throws com.example.travel.common.BadRequestException describing the first problem found */
    void validate(JsonNode metadata);
}
