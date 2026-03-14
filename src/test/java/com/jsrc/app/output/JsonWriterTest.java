package com.jsrc.app.output;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JsonWriterTest {

    @Test
    @DisplayName("Should serialize null as 'null'")
    void shouldSerializeNull() {
        assertEquals("null", JsonWriter.toJson(null));
    }

    @Test
    @DisplayName("Should serialize strings with proper escaping")
    void shouldSerializeStrings() {
        assertEquals("\"hello\"", JsonWriter.toJson("hello"));
        assertEquals("\"he said \\\"hi\\\"\"", JsonWriter.toJson("he said \"hi\""));
        assertEquals("\"line1\\nline2\"", JsonWriter.toJson("line1\nline2"));
        assertEquals("\"tab\\there\"", JsonWriter.toJson("tab\there"));
        assertEquals("\"back\\\\slash\"", JsonWriter.toJson("back\\slash"));
    }

    @Test
    @DisplayName("Should serialize numbers")
    void shouldSerializeNumbers() {
        assertEquals("42", JsonWriter.toJson(42));
        assertEquals("3.14", JsonWriter.toJson(3.14));
        assertEquals("-1", JsonWriter.toJson(-1));
        assertEquals("0", JsonWriter.toJson(0));
    }

    @Test
    @DisplayName("Should serialize booleans")
    void shouldSerializeBooleans() {
        assertEquals("true", JsonWriter.toJson(true));
        assertEquals("false", JsonWriter.toJson(false));
    }

    @Test
    @DisplayName("Should serialize lists as JSON arrays")
    void shouldSerializeLists() {
        assertEquals("[]", JsonWriter.toJson(List.of()));
        assertEquals("[1,2,3]", JsonWriter.toJson(List.of(1, 2, 3)));
        assertEquals("[\"a\",\"b\"]", JsonWriter.toJson(List.of("a", "b")));
    }

    @Test
    @DisplayName("Should serialize maps as JSON objects")
    void shouldSerializeMaps() {
        assertEquals("{}", JsonWriter.toJson(Map.of()));

        var map = new LinkedHashMap<String, Object>();
        map.put("name", "test");
        map.put("count", 5);
        map.put("active", true);
        assertEquals("{\"name\":\"test\",\"count\":5,\"active\":true}", JsonWriter.toJson(map));
    }

    @Test
    @DisplayName("Should serialize nested structures")
    void shouldSerializeNestedStructures() {
        var inner = new LinkedHashMap<String, Object>();
        inner.put("x", 1);
        var outer = new LinkedHashMap<String, Object>();
        outer.put("data", inner);
        outer.put("items", List.of("a", "b"));

        assertEquals("{\"data\":{\"x\":1},\"items\":[\"a\",\"b\"]}", JsonWriter.toJson(outer));
    }

    @Test
    @DisplayName("Should handle null values in maps")
    void shouldHandleNullInMaps() {
        var map = new LinkedHashMap<String, Object>();
        map.put("key", null);
        assertEquals("{\"key\":null}", JsonWriter.toJson(map));
    }

    @Test
    @DisplayName("Should handle null values in lists")
    void shouldHandleNullInLists() {
        var list = new java.util.ArrayList<String>();
        list.add("a");
        list.add(null);
        list.add("b");
        assertEquals("[\"a\",null,\"b\"]", JsonWriter.toJson(list));
    }
}
