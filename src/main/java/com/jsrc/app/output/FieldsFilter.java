package com.jsrc.app.output;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Filters JSON output maps to only include requested fields.
 * Reduces token usage for agents that only need specific data.
 */
public final class FieldsFilter {

    private FieldsFilter() {}

    /**
     * Filters a map to only include the specified fields.
     * Returns the original map if fields is null or empty.
     */
    public static Map<String, Object> filter(Map<String, Object> map, Set<String> fields) {
        if (fields == null || fields.isEmpty()) return map;
        Map<String, Object> filtered = new LinkedHashMap<>();
        for (String field : fields) {
            if (map.containsKey(field)) {
                filtered.put(field, map.get(field));
            }
        }
        return filtered;
    }

    /**
     * Filters each map in a collection.
     */
    public static Object filterCollection(Collection<?> items, Set<String> fields) {
        if (fields == null || fields.isEmpty()) return items;
        return items.stream()
                .map(item -> {
                    if (item instanceof Map<?, ?> map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> typedMap = (Map<String, Object>) map;
                        return filter(typedMap, fields);
                    }
                    return item;
                })
                .toList();
    }

    /**
     * Parses a comma-separated fields string.
     */
    public static Set<String> parseFields(String fieldsArg) {
        if (fieldsArg == null || fieldsArg.isBlank()) return Set.of();
        return Arrays.stream(fieldsArg.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }
}
