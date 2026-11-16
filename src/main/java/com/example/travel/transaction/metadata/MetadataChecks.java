package com.example.travel.transaction.metadata;

import com.example.travel.common.BadRequestException;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

/** Small reusable checks so each validator reads like the spec table. */
final class MetadataChecks {

    private MetadataChecks() {
    }

    static JsonNode object(JsonNode parent, String field, String path) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isObject()) {
            throw fail(path + "." + field + " must be an object");
        }
        return value;
    }

    static JsonNode nonEmptyArray(JsonNode parent, String field, String path) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isArray() || value.isEmpty()) {
            throw fail(path + "." + field + " must be a non-empty array");
        }
        return value;
    }

    static JsonNode optionalArray(JsonNode parent, String field, String path) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isArray()) {
            throw fail(path + "." + field + " must be an array");
        }
        return value;
    }

    static JsonNode element(JsonNode array, int index, String path) {
        JsonNode element = array.get(index);
        if (element == null || !element.isObject()) {
            throw fail(path + " must be an object");
        }
        return element;
    }

    static String text(JsonNode parent, String field, String path) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw fail(path + "." + field + " must be a non-empty string");
        }
        return value.asText();
    }

    static void optionalText(JsonNode parent, String field, String path) {
        JsonNode value = parent.get(field);
        if (value != null && !value.isNull() && !value.isTextual()) {
            throw fail(path + "." + field + " must be a string");
        }
    }

    static void nonNegativeNumber(JsonNode parent, String field, String path) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isNumber() || value.decimalValue().signum() < 0) {
            throw fail(path + "." + field + " must be a number >= 0");
        }
    }

    static void positiveInteger(JsonNode parent, String field, String path) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isIntegralNumber() || value.asLong() < 1) {
            throw fail(path + "." + field + " must be an integer >= 1");
        }
    }

    /** ISO-8601 date-time WITH an offset, e.g. 2026-03-11T07:00:00Z. */
    static OffsetDateTime dateTime(JsonNode parent, String field, String path) {
        String raw = text(parent, field, path);
        try {
            return OffsetDateTime.parse(raw);
        } catch (DateTimeParseException e) {
            throw fail(path + "." + field + " must be an ISO-8601 date-time with offset, e.g. 2026-03-11T07:00:00Z");
        }
    }

    /** ISO-8601 date, e.g. 2026-03-11. */
    static LocalDate date(JsonNode parent, String field, String path) {
        String raw = text(parent, field, path);
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException e) {
            throw fail(path + "." + field + " must be an ISO-8601 date, e.g. 2026-03-11");
        }
    }

    static BadRequestException fail(String message) {
        return new BadRequestException(message);
    }
}
