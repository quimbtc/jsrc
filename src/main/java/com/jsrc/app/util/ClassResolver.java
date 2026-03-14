package com.jsrc.app.util;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.jsrc.app.output.JsonWriter;
import com.jsrc.app.parser.model.ClassInfo;

/**
 * Resolves a class name to a unique ClassInfo.
 * When multiple classes share the same simple name, returns candidates
 * for disambiguation.
 */
public final class ClassResolver {

    /**
     * Result of class resolution.
     */
    public sealed interface Resolution {
        record Found(ClassInfo classInfo) implements Resolution {}
        record Ambiguous(List<String> candidates) implements Resolution {}
        record NotFound(String className) implements Resolution {}
    }

    private ClassResolver() {}

    /**
     * Resolves a class by simple or qualified name.
     *
     * @param allClasses all known classes
     * @param className  simple or qualified class name
     * @return Found, Ambiguous, or NotFound
     */
    public static Resolution resolve(List<ClassInfo> allClasses, String className) {
        // Try exact qualified match first
        for (ClassInfo ci : allClasses) {
            if (ci.qualifiedName().equals(className)) {
                return new Resolution.Found(ci);
            }
        }

        // Try simple name match
        List<ClassInfo> matches = allClasses.stream()
                .filter(ci -> ci.name().equals(className))
                .toList();

        if (matches.isEmpty()) {
            return new Resolution.NotFound(className);
        }
        if (matches.size() == 1) {
            return new Resolution.Found(matches.getFirst());
        }

        // Ambiguous — return qualified names as candidates
        List<String> candidates = matches.stream()
                .map(ClassInfo::qualifiedName)
                .sorted()
                .toList();
        return new Resolution.Ambiguous(candidates);
    }

    /**
     * Prints ambiguous result as JSON to stdout and returns exit indication.
     */
    public static void printAmbiguous(List<String> candidates, String className) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ambiguous", true);
        result.put("className", className);
        result.put("candidates", candidates);
        result.put("message", "Multiple classes named '" + className
                + "'. Use qualified name to disambiguate.");
        System.out.println(JsonWriter.toJson(result));
    }
}
