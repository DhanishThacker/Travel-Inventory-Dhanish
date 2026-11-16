package com.example.travel.transaction;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** A stored transaction as returned by the API. */
public record Transaction(
        String id,
        String type,
        @JsonProperty("external_id") String externalId,
        String currency,
        @JsonProperty("occurred_at") OffsetDateTime occurredAt,
        BigDecimal total,
        @JsonProperty("tax_lines") List<TaxLine> taxLines,
        @JsonProperty("fee_lines") List<FeeLine> feeLines,
        JsonNode metadata,
        @JsonProperty("created_at") OffsetDateTime createdAt,
        @JsonProperty("updated_at") OffsetDateTime updatedAt) {
}
