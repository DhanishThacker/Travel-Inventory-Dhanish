package com.example.travel.transaction.metadata;

import com.example.travel.transaction.TransactionType;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Component;

/** rail: one or more legs (a change of train is two legs), not just a single origin/destination pair. */
@Component
public class RailMetadataValidator implements MetadataValidator {

    @Override
    public TransactionType type() {
        return TransactionType.RAIL;
    }

    @Override
    public void validate(JsonNode metadata) {
        String root = "metadata";
        JsonNode legs = MetadataChecks.nonEmptyArray(metadata, "legs", root);
        for (int i = 0; i < legs.size(); i++) {
            String path = root + ".legs[" + i + "]";
            JsonNode leg = MetadataChecks.element(legs, i, path);
            MetadataChecks.text(leg, "origin", path);
            MetadataChecks.text(leg, "destination", path);
            OffsetDateTime departure = MetadataChecks.dateTime(leg, "departure", path);
            OffsetDateTime arrival = MetadataChecks.dateTime(leg, "arrival", path);
            if (arrival.isBefore(departure)) {
                throw MetadataChecks.fail(path + ".arrival must not be before departure");
            }
            MetadataChecks.optionalText(leg, "train_number", path);
            MetadataChecks.optionalText(leg, "class", path);
        }
    }
}
