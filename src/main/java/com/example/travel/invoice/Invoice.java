package com.example.travel.invoice;

import com.example.travel.transaction.FeeLine;
import com.example.travel.transaction.TaxLine;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public record Invoice(
        String id,
        String currency,
        BigDecimal total,
        @JsonProperty("created_at") OffsetDateTime createdAt,
        @JsonProperty("updated_at") OffsetDateTime updatedAt,
        List<InvoiceLine> lines,
        @JsonProperty("tax_rollup") List<TaxLine> taxRollup,
        @JsonProperty("fee_rollup") List<FeeLine> feeRollup,
        InvoiceTotals totals) {
}
