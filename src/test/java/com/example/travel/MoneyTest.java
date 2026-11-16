package com.example.travel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.travel.common.BadRequestException;
import com.example.travel.common.Money;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void normalisesToScaleTwoWithoutChangingTheValue() {
        assertThat(Money.amount(new BigDecimal("331.7"), "total").toPlainString()).isEqualTo("331.70");
        assertThat(Money.amount(new BigDecimal("220.000"), "total").toPlainString()).isEqualTo("220.00");
        assertThat(Money.amount(new BigDecimal("0"), "total").toPlainString()).isEqualTo("0.00");
    }

    @Test
    void rejectsInsteadOfRounding() {
        assertThatThrownBy(() -> Money.amount(new BigDecimal("1.005"), "total"))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> Money.amount(new BigDecimal("-0.01"), "total"))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> Money.amount(null, "total"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void ratesAreCanonicalised() {
        assertThat(Money.displayRate(new BigDecimal("0.0700")).toPlainString()).isEqualTo("0.07");
        assertThat(Money.rate(new BigDecimal("0.07"), "rate").toPlainString()).isEqualTo("0.0700");
        assertThat(Money.rate(null, "rate")).isNull();
        assertThatThrownBy(() -> Money.rate(new BigDecimal("0.123456"), "rate"))
                .isInstanceOf(BadRequestException.class);
    }
}
