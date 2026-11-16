package com.example.travel.transaction;

import java.util.Optional;

public enum TransactionType {
    FLIGHT("flight"),
    HOTEL("hotel"),
    RAIL("rail"),
    NAVAN_FEE("navan_fee");

    private final String wire;

    TransactionType(String wire) {
        this.wire = wire;
    }

    /** The value used in JSON and in the database. */
    public String wire() {
        return wire;
    }

    public static Optional<TransactionType> fromWire(String value) {
        for (TransactionType type : values()) {
            if (type.wire.equals(value)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
