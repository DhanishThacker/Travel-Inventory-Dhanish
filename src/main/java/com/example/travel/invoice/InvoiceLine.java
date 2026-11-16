package com.example.travel.invoice;

import com.example.travel.transaction.FeeLine;
import com.example.travel.transaction.TaxLine;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** A frozen copy of one transaction as it was when the invoice was created / rebuilt. */
public record InvoiceLine(
        @JsonProperty("transaction_id") String transactionId,
        @JsonProperty("external_id") String externalId,
        String type,
        String currency,
        @JsonProperty("occurred_at") OffsetDateTime occurredAt,
        BigDecimal total,
        @JsonProperty("tax_lines") List<TaxLine> taxLines,
        @JsonProperty("fee_lines") List<FeeLine> feeLines,
        JsonNode metadata) {
}
