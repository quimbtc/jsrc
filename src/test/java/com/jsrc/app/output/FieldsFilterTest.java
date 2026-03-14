package com.jsrc.app.output;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FieldsFilterTest {

    @Test
    @DisplayName("Should filter map to only requested fields")
    void shouldFilterMap() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("name", "OrderService");
        input.put("packageName", "com.app");
        input.put("startLine", 10);
        input.put("methodCount", 5);

        Map<String, Object> filtered = FieldsFilter.filter(input, Set.of("name", "methodCount"));
        assertEquals(2, filtered.size());
        assertEquals("OrderService", filtered.get("name"));
        assertEquals(5, filtered.get("methodCount"));
        assertNull(filtered.get("packageName"));
    }

    @Test
    @DisplayName("Should filter list of maps")
    void shouldFilterList() {
        var item1 = new LinkedHashMap<String, Object>();
        item1.put("name", "A");
        item1.put("extra", "x");
        var item2 = new LinkedHashMap<String, Object>();
        item2.put("name", "B");
        item2.put("extra", "y");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> filtered = (List<Map<String, Object>>)
                FieldsFilter.filterCollection(List.of(item1, item2), Set.of("name"));

        assertEquals(2, filtered.size());
        assertEquals(1, filtered.get(0).size());
        assertEquals("A", filtered.get(0).get("name"));
    }

    @Test
    @DisplayName("Should pass through if no fields specified")
    void shouldPassThroughWhenNoFields() {
        Map<String, Object> input = Map.of("name", "test");
        assertSame(input, FieldsFilter.filter(input, null));
        assertSame(input, FieldsFilter.filter(input, Set.of()));
    }

    @Test
    @DisplayName("Should parse comma-separated fields string")
    void shouldParseFieldsString() {
        Set<String> fields = FieldsFilter.parseFields("name,packageName,methodCount");
        assertEquals(3, fields.size());
        assertTrue(fields.contains("name"));
        assertTrue(fields.contains("packageName"));
        assertTrue(fields.contains("methodCount"));
    }

    @Test
    @DisplayName("Should handle whitespace in fields string")
    void shouldHandleWhitespace() {
        Set<String> fields = FieldsFilter.parseFields(" name , packageName ");
        assertEquals(2, fields.size());
        assertTrue(fields.contains("name"));
    }
}
