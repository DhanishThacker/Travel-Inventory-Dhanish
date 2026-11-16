package com.example.travel;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.travel.invoice.InvoiceRollupCalculator;
import com.example.travel.transaction.FeeLine;
import com.example.travel.transaction.TaxLine;
import com.example.travel.transaction.Transaction;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Pure unit tests: no Spring, no database. */
class InvoiceRollupCalculatorTest {

    private static Transaction txn(String id, String type, String total, List<TaxLine> tax, List<FeeLine> fee) {
        OffsetDateTime now = OffsetDateTime.now();
        return new Transaction(id, type, "ext-" + id, "EUR", now, new BigDecimal(total), tax, fee,
                JsonNodeFactory.instance.objectNode(), now, now);
    }

    private static TaxLine tax(String name, String rate, String amount) {
        return new TaxLine(name, rate == null ? null : new BigDecimal(rate), new BigDecimal(amount));
    }

    private static FeeLine fee(String name, String amount) {
        return new FeeLine(name, new BigDecimal(amount));
    }

    @Test
    void tripFromTheSeedFixtures() {
        var rollup = InvoiceRollupCalculator.compute(List.of(
                txn("f", "flight", "220.00", List.of(tax("Airport tax", null, "15.00")), List.of(fee("YQ", "25.00"))),
                txn("h", "hotel", "331.70", List.of(tax("VAT", "0.07", "21.70")), List.of()),
                txn("r", "rail", "94.00", List.of(), List.of(fee("Booking fee", "4.00"))),
                txn("t", "navan_fee", "25.00", List.of(), List.of()),
                txn("a", "navan_fee", "15.00", List.of(), List.of())));

        assertThat(rollup.total()).isEqualByComparingTo("685.70");
        assertThat(rollup.taxTotal()).isEqualByComparingTo("36.70");
        assertThat(rollup.feeTotal()).isEqualByComparingTo("29.00");
        assertThat(rollup.taxRollup()).hasSize(2);
        assertThat(rollup.feeRollup()).hasSize(2);
        assertThat(rollup.total().scale()).isEqualTo(2);
    }

    @Test
    void sameTaxNameAndRateAreMergedButDifferentRatesStaySeparate() {
        var rollup = InvoiceRollupCalculator.compute(List.of(
                txn("a", "hotel", "10.00", List.of(tax("VAT", "0.0700", "1.00")), List.of()),
                txn("b", "hotel", "10.00",
                        List.of(tax("VAT", "0.07", "2.50"), tax("VAT", "0.19", "3.00")), List.of())));

        assertThat(rollup.taxRollup()).hasSize(2);
        assertThat(rollup.taxRollup().get(0).amount()).isEqualByComparingTo("3.50");
        assertThat(rollup.taxRollup().get(1).amount()).isEqualByComparingTo("3.00");
    }

    @Test
    void noMoneyIsLostToBinaryFloatingPoint() {
        var rollup = InvoiceRollupCalculator.compute(List.of(
                txn("a", "flight", "0.10", List.of(), List.of()),
                txn("b", "flight", "0.20", List.of(), List.of())));
        assertThat(rollup.total().toPlainString()).isEqualTo("0.30");
    }

    @Test
    void emptyInputGivesZeroes() {
        var rollup = InvoiceRollupCalculator.compute(List.of());
        assertThat(rollup.total().toPlainString()).isEqualTo("0.00");
        assertThat(rollup.taxRollup()).isEmpty();
        assertThat(rollup.feeRollup()).isEmpty();
    }
}
