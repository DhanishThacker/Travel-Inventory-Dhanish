package com.example.travel.transaction.metadata;

import com.example.travel.transaction.TransactionType;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import org.springframework.stereotype.Component;

/** hotel: property name + address, stay dates, nights, nightly rate, optional extras. */
@Component
public class HotelMetadataValidator implements MetadataValidator {

    @Override
    public TransactionType type() {
        return TransactionType.HOTEL;
    }

    @Override
    public void validate(JsonNode metadata) {
        String root = "metadata";
        MetadataChecks.text(metadata, "name", root);

        JsonNode address = MetadataChecks.object(metadata, "address", root);
        MetadataChecks.text(address, "street", root + ".address");
        MetadataChecks.text(address, "city", root + ".address");
        MetadataChecks.text(address, "country", root + ".address");

        LocalDate checkIn = MetadataChecks.date(metadata, "check_in", root);
        LocalDate checkOut = MetadataChecks.date(metadata, "check_out", root);
        if (!checkOut.isAfter(checkIn)) {
            throw MetadataChecks.fail(root + ".check_out must be after check_in");
        }
        MetadataChecks.positiveInteger(metadata, "nights", root);
        MetadataChecks.nonNegativeNumber(metadata, "room_rate_per_night", root);

        JsonNode extras = MetadataChecks.optionalArray(metadata, "extras", root);
        if (extras != null) {
            for (int i = 0; i < extras.size(); i++) {
                String path = root + ".extras[" + i + "]";
                JsonNode extra = MetadataChecks.element(extras, i, path);
                MetadataChecks.text(extra, "name", path);
                MetadataChecks.nonNegativeNumber(extra, "amount", path);
            }
        }
    }
}
