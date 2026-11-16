package com.example.travel.transaction.metadata;

import com.example.travel.common.BadRequestException;
import com.example.travel.transaction.TransactionType;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Registry: picks the validator for a transaction type. Adding a type = adding one validator bean. */
@Component
public class MetadataValidators {

    private final Map<TransactionType, MetadataValidator> byType = new EnumMap<>(TransactionType.class);

    public MetadataValidators(List<MetadataValidator> validators) {
        for (MetadataValidator validator : validators) {
            byType.put(validator.type(), validator);
        }
    }

    public void validate(TransactionType type, JsonNode metadata) {
        if (metadata == null || !metadata.isObject()) {
            throw new BadRequestException("metadata must be a JSON object");
        }
        MetadataValidator validator = byType.get(type);
        if (validator == null) {
            throw new IllegalStateException("No metadata validator registered for " + type);
        }
        validator.validate(metadata);
    }
}
