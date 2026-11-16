package com.example.travel.transaction.metadata;

import com.example.travel.transaction.TransactionType;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Component;

/** flight: origin, destination, fare and at least one coupon (segment) with a flight number. */
@Component
public class FlightMetadataValidator implements MetadataValidator {

    @Override
    public TransactionType type() {
        return TransactionType.FLIGHT;
    }

    @Override
    public void validate(JsonNode metadata) {
        String root = "metadata";
        MetadataChecks.text(metadata, "origin", root);
        MetadataChecks.text(metadata, "destination", root);
        MetadataChecks.nonNegativeNumber(metadata, "fare", root);

        JsonNode coupons = MetadataChecks.nonEmptyArray(metadata, "coupons", root);
        for (int i = 0; i < coupons.size(); i++) {
            String path = root + ".coupons[" + i + "]";
            JsonNode coupon = MetadataChecks.element(coupons, i, path);
            MetadataChecks.text(coupon, "flight_number", path);
            MetadataChecks.optionalText(coupon, "carrier", path);
            MetadataChecks.text(coupon, "from", path);
            MetadataChecks.text(coupon, "to", path);
            OffsetDateTime departure = MetadataChecks.dateTime(coupon, "departure", path);
            OffsetDateTime arrival = MetadataChecks.dateTime(coupon, "arrival", path);
            if (arrival.isBefore(departure)) {
                throw MetadataChecks.fail(path + ".arrival must not be before departure");
            }
        }
    }
}
