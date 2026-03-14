package com.jsrc.app.output;

import java.util.Collection;
import java.util.Iterator;
import java.util.Map;

/**
 * Minimal JSON serializer with no external dependencies.
 * Handles strings, numbers, booleans, nulls, lists, and maps.
 */
public final class JsonWriter {

    private JsonWriter() {}

    /**
     * Serializes a value to a compact JSON string.
     *
     * @param value any supported type (String, Number, Boolean, null,
     *              Collection, Map&lt;String,?&gt;)
     * @return JSON string representation
     */
    public static String toJson(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void writeValue(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            writeString(sb, s);
        } else if (value instanceof Number n) {
            sb.append(n);
        } else if (value instanceof Boolean b) {
            sb.append(b);
        } else if (value instanceof Map<?, ?> map) {
            writeMap(sb, (Map<String, Object>) map);
        } else if (value instanceof Collection<?> coll) {
            writeArray(sb, coll);
        } else {
            writeString(sb, value.toString());
        }
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    private static void writeMap(StringBuilder sb, Map<String, Object> map) {
        sb.append('{');
        Iterator<Map.Entry<String, Object>> it = map.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Object> entry = it.next();
            writeString(sb, entry.getKey());
            sb.append(':');
            writeValue(sb, entry.getValue());
            if (it.hasNext()) sb.append(',');
        }
        sb.append('}');
    }

    private static void writeArray(StringBuilder sb, Collection<?> coll) {
        sb.append('[');
        Iterator<?> it = coll.iterator();
        while (it.hasNext()) {
            writeValue(sb, it.next());
            if (it.hasNext()) sb.append(',');
        }
        sb.append(']');
    }
}
