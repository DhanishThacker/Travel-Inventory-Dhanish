package com.example.travel.transaction;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Body of POST /transactions. Envelope-level checks happen here (Bean Validation);
 * money scale, line contents and type-specific metadata are checked in the service.
 * Unknown top-level properties are ignored on purpose (the seed fixtures carry a "comment" field).
 */
public record CreateTransactionRequest(
        @NotBlank String type,
        @JsonProperty("external_id") @NotBlank @Size(max = 255) String externalId,
        @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "must be a 3-letter upper-case ISO currency code") String currency,
        @JsonProperty("occurred_at") @NotNull OffsetDateTime occurredAt,
        @NotNull BigDecimal total,
        @JsonProperty("tax_lines") @NotNull List<TaxLine> taxLines,
        @JsonProperty("fee_lines") @NotNull List<FeeLine> feeLines,
        @NotNull JsonNode metadata) {
}
