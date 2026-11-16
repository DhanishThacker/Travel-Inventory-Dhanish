package com.example.travel.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** One bounded page. {@code has_more} instead of a total count: COUNT(*) on a big table is not cheap. */
public record Page<T>(
        List<T> items,
        int limit,
        int offset,
        @JsonProperty("has_more") boolean hasMore) {
}
