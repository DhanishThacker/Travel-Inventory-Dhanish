package com.example.travel.common;

/** Request conflicts with current state (maps to HTTP 409). {@code code} is a stable machine-readable reason. */
public class ConflictException extends RuntimeException {

    private final String code;

    public ConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
