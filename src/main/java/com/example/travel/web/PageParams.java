package com.example.travel.web;

import com.example.travel.common.BadRequestException;

/** Validated pagination input. limit defaults to 50 and is capped at 100 (larger values are a 400). */
public record PageParams(int limit, int offset) {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 100;

    public static PageParams of(int limit, int offset) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new BadRequestException("limit must be between 1 and " + MAX_LIMIT);
        }
        if (offset < 0) {
            throw new BadRequestException("offset must be >= 0");
        }
        return new PageParams(limit, offset);
    }
}
