package com.example.travel.invoice;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Row of GET /invoices: header only, so list queries stay cheap. */
public record InvoiceSummary(
        String id,
        String currency,
        BigDecimal total,
        @JsonProperty("line_count") int lineCount,
        @JsonProperty("created_at") OffsetDateTime createdAt,
        @JsonProperty("updated_at") OffsetDateTime updatedAt) {
}
