package com.example.travel.common;

/** Client sent something invalid (maps to HTTP 400). */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
