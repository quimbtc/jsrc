package com.jsrc.app.parser.model;

import java.util.Map;

/**
 * Represents a Java annotation with its attributes.
 *
 * @param name       annotation simple name (e.g. "Override", "Transactional")
 * @param attributes key-value pairs for annotation members; single-value annotations
 *                   use "value" as key. Empty map for marker annotations.
 */
public record AnnotationInfo(
        String name,
        Map<String, String> attributes
) {
    public static AnnotationInfo marker(String name) {
        return new AnnotationInfo(name, Map.of());
    }

    public boolean isMarker() {
        return attributes.isEmpty();
    }

    @Override
    public String toString() {
        if (isMarker()) return "@" + name;
        if (attributes.size() == 1 && attributes.containsKey("value")) {
            return "@" + name + "(" + attributes.get("value") + ")";
        }
        StringBuilder sb = new StringBuilder("@").append(name).append("(");
        var entries = attributes.entrySet().iterator();
        while (entries.hasNext()) {
            var e = entries.next();
            sb.append(e.getKey()).append("=").append(e.getValue());
            if (entries.hasNext()) sb.append(", ");
        }
        return sb.append(")").toString();
    }
}
