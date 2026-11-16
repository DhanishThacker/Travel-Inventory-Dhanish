package com.example.travel.transaction;

import java.math.BigDecimal;

/** Carrier / merchant fee on a transaction (YQ-style surcharge, booking fee, ...). Kept apart from taxes. */
public record FeeLine(String name, BigDecimal amount) {
}
