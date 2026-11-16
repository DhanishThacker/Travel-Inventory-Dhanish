package com.example.travel.invoice;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Body of POST /invoices and PATCH /invoices/{id}: exactly one of the two fields. */
public record InvoiceRequest(
        @JsonProperty("transaction_id") String transactionId,
        @JsonProperty("transaction_ids") List<String> transactionIds) {
}
