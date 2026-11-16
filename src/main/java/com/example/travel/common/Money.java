package com.example.travel.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Money rules in one place.
 *
 * <ul>
 *   <li>Amounts are {@link BigDecimal}, scale 2, stored as DECIMAL(19,2). Never double/float.</li>
 *   <li>Input with more than 2 significant decimals is rejected (400) rather than silently rounded,
 *       so no one's money changes by accident. "220.000" is fine (no information lost).</li>
 *   <li>Tax rates are scale 4 (DECIMAL(9,4)), shown without trailing zeros (0.07, not 0.0700).</li>
 *   <li>Amounts must be &gt;= 0 (refunds / credit notes are out of scope).</li>
 * </ul>
 */
public final class Money {

    /** 0.00 */
    public static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    private static final int MAX_AMOUNT_INTEGER_DIGITS = 17; // DECIMAL(19,2)
    private static final int MAX_RATE_INTEGER_DIGITS = 5;    // DECIMAL(9,4)

    private Money() {
    }

    public static BigDecimal amount(BigDecimal value, String field) {
        if (value == null) {
            throw new BadRequestException(field + " is required");
        }
        if (value.signum() < 0) {
            throw new BadRequestException(field + " must not be negative");
        }
        if (value.signum() != 0 && value.stripTrailingZeros().scale() > 2) {
            throw new BadRequestException(field + " must have at most 2 decimal places");
        }
        BigDecimal scaled = value.setScale(2, RoundingMode.UNNECESSARY);
        if (scaled.precision() - scaled.scale() > MAX_AMOUNT_INTEGER_DIGITS) {
            throw new BadRequestException(field + " is too large");
        }
        return scaled;
    }

    /** Optional tax rate (e.g. 0.07 for 7%). Null stays null. */
    public static BigDecimal rate(BigDecimal value, String field) {
        if (value == null) {
            return null;
        }
        if (value.signum() < 0) {
            throw new BadRequestException(field + " must not be negative");
        }
        if (value.signum() != 0 && value.stripTrailingZeros().scale() > 4) {
            throw new BadRequestException(field + " must have at most 4 decimal places");
        }
        BigDecimal scaled = value.setScale(4, RoundingMode.UNNECESSARY);
        if (scaled.precision() - scaled.scale() > MAX_RATE_INTEGER_DIGITS) {
            throw new BadRequestException(field + " is too large");
        }
        return scaled;
    }

    /** Canonical form of a rate for output and for grouping: 0.0700 -> 0.07, 1.0000 -> 1. */
    public static BigDecimal displayRate(BigDecimal rate) {
        if (rate == null) {
            return null;
        }
        BigDecimal stripped = rate.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }
}
