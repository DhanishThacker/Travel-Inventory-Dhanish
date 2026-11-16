package com.example.travel.transaction;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Body of PATCH /transactions/{id}. Every field is optional; a provided list or metadata
 * REPLACES the stored one (no deep merge). type, external_id and currency are immutable.
 */
public record PatchTransactionRequest(
        @JsonProperty("occurred_at") OffsetDateTime occurredAt,
        BigDecimal total,
        @JsonProperty("tax_lines") List<TaxLine> taxLines,
        @JsonProperty("fee_lines") List<FeeLine> feeLines,
        JsonNode metadata) {

    @JsonIgnore
    public boolean hasMetadata() {
        return metadata != null && !metadata.isNull();
    }

    @JsonIgnore
    public boolean hasChanges() {
        return occurredAt != null || total != null || taxLines != null || feeLines != null || hasMetadata();
    }
}
