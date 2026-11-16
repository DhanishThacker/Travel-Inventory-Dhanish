package com.example.travel.invoice;

import com.example.travel.common.Money;
import com.example.travel.transaction.FeeLine;
import com.example.travel.transaction.TaxLine;
import com.example.travel.transaction.Transaction;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure function: transactions in, totals and rollups out. No database, no clock.
 *
 * <p>Grouping rules (documented in ARCHITECTURE.md):
 * <ul>
 *   <li>tax rollup: group by (name, rate); a tax without a rate is its own group</li>
 *   <li>fee rollup: group by name</li>
 *   <li>names are compared exactly (case-sensitive); groups keep first-seen order</li>
 *   <li>grand total = sum of transaction totals (gross)</li>
 * </ul>
 */
public final class InvoiceRollupCalculator {

    private InvoiceRollupCalculator() {
    }

    public record Rollup(BigDecimal total, BigDecimal taxTotal, BigDecimal feeTotal,
                         List<TaxLine> taxRollup, List<FeeLine> feeRollup) {
    }

    private record TaxKey(String name, BigDecimal rate) {
    }

    public static Rollup compute(List<Transaction> transactions) {
        BigDecimal total = Money.ZERO;
        BigDecimal taxTotal = Money.ZERO;
        BigDecimal feeTotal = Money.ZERO;
        Map<TaxKey, BigDecimal> taxes = new LinkedHashMap<>();
        Map<String, BigDecimal> fees = new LinkedHashMap<>();

        for (Transaction t : transactions) {
            total = total.add(t.total());
            for (TaxLine line : t.taxLines()) {
                taxTotal = taxTotal.add(line.amount());
                taxes.merge(new TaxKey(line.name(), Money.displayRate(line.rate())), line.amount(), BigDecimal::add);
            }
            for (FeeLine line : t.feeLines()) {
                feeTotal = feeTotal.add(line.amount());
                fees.merge(line.name(), line.amount(), BigDecimal::add);
            }
        }

        List<TaxLine> taxRollup = new ArrayList<>();
        taxes.forEach((key, amount) -> taxRollup.add(new TaxLine(key.name(), key.rate(), amount)));
        List<FeeLine> feeRollup = new ArrayList<>();
        fees.forEach((name, amount) -> feeRollup.add(new FeeLine(name, amount)));

        return new Rollup(total, taxTotal, feeTotal, taxRollup, feeRollup);
    }
}
