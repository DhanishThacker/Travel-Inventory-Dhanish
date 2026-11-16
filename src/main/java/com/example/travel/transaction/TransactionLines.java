package com.example.travel.transaction;

import com.example.travel.common.BadRequestException;
import com.example.travel.common.Money;
import java.util.ArrayList;
import java.util.List;

/** Validates and normalises tax / fee lines coming from a request. */
final class TransactionLines {

    private static final int MAX_NAME_LENGTH = 100;

    private TransactionLines() {
    }

    static List<TaxLine> tax(List<TaxLine> input, String field) {
        if (input == null) {
            throw new BadRequestException(field + " is required (use [] for none)");
        }
        List<TaxLine> out = new ArrayList<>(input.size());
        for (int i = 0; i < input.size(); i++) {
            TaxLine line = input.get(i);
            String path = field + "[" + i + "]";
            if (line == null) {
                throw new BadRequestException(path + " must not be null");
            }
            out.add(new TaxLine(
                    name(line.name(), path + ".name"),
                    Money.rate(line.rate(), path + ".rate"),
                    Money.amount(line.amount(), path + ".amount")));
        }
        return out;
    }

    static List<FeeLine> fee(List<FeeLine> input, String field) {
        if (input == null) {
            throw new BadRequestException(field + " is required (use [] for none)");
        }
        List<FeeLine> out = new ArrayList<>(input.size());
        for (int i = 0; i < input.size(); i++) {
            FeeLine line = input.get(i);
            String path = field + "[" + i + "]";
            if (line == null) {
                throw new BadRequestException(path + " must not be null");
            }
            out.add(new FeeLine(
                    name(line.name(), path + ".name"),
                    Money.amount(line.amount(), path + ".amount")));
        }
        return out;
    }

    private static String name(String name, String path) {
        if (name == null || name.isBlank()) {
            throw new BadRequestException(path + " is required");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new BadRequestException(path + " must be at most " + MAX_NAME_LENGTH + " characters");
        }
        return name;
    }
}
