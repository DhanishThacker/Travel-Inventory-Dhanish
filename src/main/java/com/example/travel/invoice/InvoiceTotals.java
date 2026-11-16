package com.example.travel.invoice;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.Map;

/**
 * grand_total = sum of line totals (which are gross: they already include their tax and fee lines).
 * tax_total / fee_total are informational sums of the rollups. by_type is derived from the frozen lines.
 */
public record InvoiceTotals(
        @JsonProperty("grand_total") BigDecimal grandTotal,
        @JsonProperty("tax_total") BigDecimal taxTotal,
        @JsonProperty("fee_total") BigDecimal feeTotal,
        @JsonProperty("by_type") Map<String, BigDecimal> byType) {
}
