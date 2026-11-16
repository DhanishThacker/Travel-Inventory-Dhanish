package com.example.travel.transaction;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;

/** Government / VAT style tax. {@code rate} is optional (e.g. a flat airport tax has none). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaxLine(String name, BigDecimal rate, BigDecimal amount) {
}
