package com.jsrc.app.command;

import java.util.LinkedHashMap;
import java.util.Map;

import com.jsrc.app.parser.model.ClassInfo;

/**
 * Ultra-compact code style guide extracted from the codebase.
 * Target: &lt;300 chars — fits in any model's context window.
 * <p>
 * Analyzes imports, annotations, constructor patterns, and method naming
 * across all indexed classes to detect project conventions.
 */
public class StyleCommand implements Command {

    @Override
    public int execute(CommandContext ctx) {
        var allClasses = ctx.getAllClasses();
        if (allClasses.isEmpty()) {
            ctx.formatter().printResult(Map.of("error", "No classes found"));
            return 0;
        }

        int slf4j = 0, log4j = 0, sysout = 0;
        int ctorInjection = 0, fieldInjection = 0;
        int requireNonNull = 0, optionalReturns = 0;
        int totalClasses = allClasses.size();

        // Scan index for patterns
        if (ctx.indexed() != null) {
            for (var entry : ctx.indexed().getEntries()) {
                for (var ic : entry.classes()) {
                    // Logging
                    for (String imp : ic.imports()) {
                        if (imp.contains("slf4j")) slf4j++;
                        else if (imp.contains("log4j")) log4j++;
                    }

                    // Injection: constructor with params vs @Autowired fields
                    boolean hasCtorParams = ic.methods().stream()
                            .anyMatch(m -> m.name().equals(ic.name())
                                    && m.signature() != null
                                    && m.signature().contains(","));
                    boolean hasAutowired = ic.annotations().contains("Autowired")
                            || ic.fields().stream().anyMatch(f ->
                                    ic.annotations().contains("Autowired"));
                    if (hasCtorParams) ctorInjection++;
                    if (hasAutowired) fieldInjection++;

                    // Null handling
                    for (var m : ic.methods()) {
                        if (m.signature() != null && m.signature().contains("requireNonNull"))
                            requireNonNull++;
                        if (m.returnType() != null && m.returnType().startsWith("Optional"))
                            optionalReturns++;
                    }
                }
            }
        }

        // Detect System.out usage via search (not in imports)
        // Skip — too expensive. Use import-based detection only.

        // Build compact result
        Map<String, Object> result = new LinkedHashMap<>();

        // Java version
        String javaVersion = ctx.config() != null && !ctx.config().javaVersion().isEmpty()
                ? ctx.config().javaVersion() : "unknown";
        result.put("java", javaVersion);

        // Logging
        String logging;
        if (slf4j > log4j && slf4j > sysout) logging = "SLF4J";
        else if (log4j > slf4j) logging = "Log4j";
        else if (sysout > 0) logging = "System.out";
        else if (slf4j > 0) logging = "SLF4J";
        else logging = "none";
        result.put("logging", logging);

        // Injection
        String injection;
        if (ctorInjection > fieldInjection) injection = "constructor";
        else if (fieldInjection > ctorInjection) injection = "field";
        else if (ctorInjection > 0) injection = "mixed";
        else injection = "none";
        result.put("injection", injection);

        // Nulls
        StringBuilder nulls = new StringBuilder();
        if (requireNonNull > 0) nulls.append("requireNonNull");
        if (optionalReturns > 0) {
            if (!nulls.isEmpty()) nulls.append("+");
            nulls.append("Optional");
        }
        result.put("nulls", nulls.isEmpty() ? "unchecked" : nulls.toString());

        // Naming: extract top method prefixes
        Map<String, Integer> prefixes = new LinkedHashMap<>();
        for (var ci : allClasses) {
            for (var m : ci.methods()) {
                String name = m.name();
                for (String p : new String[]{"get", "set", "is", "has", "find", "create", "delete", "update"}) {
                    if (name.startsWith(p) && name.length() > p.length()
                            && Character.isUpperCase(name.charAt(p.length()))) {
                        prefixes.merge(p, 1, Integer::sum);
                    }
                }
            }
        }
        String topPrefixes = prefixes.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(4)
                .map(e -> e.getKey() + "*")
                .reduce((a, b) -> a + "/" + b)
                .orElse("mixed");
        result.put("naming", topPrefixes);

        // Collections — detect from imports
        boolean usesListOf = false, usesCollections = false;
        if (ctx.indexed() != null) {
            for (var entry : ctx.indexed().getEntries()) {
                for (var ic : entry.classes()) {
                    for (String imp : ic.imports()) {
                        if (imp.contains("java.util.List")) usesListOf = true;
                        if (imp.contains("java.util.Collections")) usesCollections = true;
                    }
                }
            }
        }
        result.put("collections", usesListOf ? "List.of()" : usesCollections ? "Collections" : "mixed");

        // Classes count for context
        result.put("classes", totalClasses);

        ctx.formatter().printResult(result);
        return 1;
    }
}
