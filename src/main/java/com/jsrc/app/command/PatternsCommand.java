package com.jsrc.app.command;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Deep analysis of codebase conventions: logging, injection, naming,
 * annotations, null handling, error handling, collections.
 * More detailed than --style, ~800 chars output.
 */
public class PatternsCommand implements Command {

    @Override
    public int execute(CommandContext ctx) {
        var allClasses = ctx.getAllClasses();
        if (allClasses.isEmpty()) {
            ctx.formatter().printResult(Map.of("error", "No classes found"));
            return 0;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        int totalClasses = allClasses.size();

        // === Logging ===
        Map<String, Integer> loggingCounts = new HashMap<>();
        if (ctx.indexed() != null) {
            for (var entry : ctx.indexed().getEntries()) {
                for (var ic : entry.classes()) {
                    for (String imp : ic.imports()) {
                        if (imp.contains("slf4j")) loggingCounts.merge("SLF4J", 1, Integer::sum);
                        else if (imp.contains("log4j")) loggingCounts.merge("Log4j", 1, Integer::sum);
                        else if (imp.contains("java.util.logging")) loggingCounts.merge("JUL", 1, Integer::sum);
                    }
                }
            }
        }
        String topLogging = loggingCounts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(e -> e.getKey() + " (" + percent(e.getValue(), totalClasses) + ")")
                .orElse("none detected");
        result.put("logging", topLogging);

        // === Injection ===
        int ctorInjection = 0, fieldInjection = 0;
        if (ctx.indexed() != null) {
            for (var entry : ctx.indexed().getEntries()) {
                for (var ic : entry.classes()) {
                    boolean hasCtor = ic.methods().stream()
                            .anyMatch(m -> m.name().equals(ic.name()) && m.signature() != null
                                    && m.signature().contains(","));
                    if (hasCtor) ctorInjection++;
                    if (ic.annotations().stream().anyMatch(a -> a.contains("Autowired") || a.contains("Inject")))
                        fieldInjection++;
                }
            }
        }
        Map<String, Object> injection = new LinkedHashMap<>();
        injection.put("constructor", percent(ctorInjection, totalClasses));
        injection.put("field", percent(fieldInjection, totalClasses));
        result.put("injection", injection);

        // === Naming conventions ===
        Map<String, Integer> classSuffixes = new HashMap<>();
        Map<String, Integer> methodPrefixes = new HashMap<>();
        for (var ci : allClasses) {
            // Class suffixes — auto-discovered, not hardcoded
            String name = ci.name();
            String suffix = extractSuffix(name);
            if (suffix != null) {
                classSuffixes.merge(suffix, 1, Integer::sum);
            }
            // Method prefixes
            for (var m : ci.methods()) {
                for (String p : new String[]{"get", "set", "is", "has", "find", "create",
                        "delete", "update", "add", "remove"}) {
                    if (m.name().startsWith(p) && m.name().length() > p.length()
                            && Character.isUpperCase(m.name().charAt(p.length()))) {
                        methodPrefixes.merge(p, 1, Integer::sum);
                    }
                }
            }
        }
        Map<String, Object> naming = new LinkedHashMap<>();
        naming.put("classSuffixes", topN(classSuffixes, 5));
        naming.put("methodPrefixes", topN(methodPrefixes, 5));
        result.put("naming", naming);

        // === Annotations top-10 ===
        Map<String, Integer> annotationCounts = new HashMap<>();
        if (ctx.indexed() != null) {
            for (var entry : ctx.indexed().getEntries()) {
                for (var ic : entry.classes()) {
                    for (String ann : ic.annotations()) {
                        annotationCounts.merge("@" + ann, 1, Integer::sum);
                    }
                    for (var m : ic.methods()) {
                        for (String ann : m.annotations()) {
                            annotationCounts.merge("@" + ann, 1, Integer::sum);
                        }
                    }
                }
            }
        }
        result.put("annotations", topN(annotationCounts, 10));

        // === Null handling ===
        int requireNonNull = 0, optionalReturns = 0;
        for (var ci : allClasses) {
            for (var m : ci.methods()) {
                if (m.returnType() != null && m.returnType().startsWith("Optional")) optionalReturns++;
            }
        }
        if (ctx.indexed() != null) {
            for (var entry : ctx.indexed().getEntries()) {
                for (var ic : entry.classes()) {
                    for (var m : ic.methods()) {
                        if (m.signature() != null && m.signature().contains("requireNonNull"))
                            requireNonNull++;
                    }
                }
            }
        }
        Map<String, Object> nullHandling = new LinkedHashMap<>();
        nullHandling.put("requireNonNull", requireNonNull);
        nullHandling.put("optionalReturns", optionalReturns);
        result.put("nullHandling", nullHandling);

        // === Error handling ===
        long customExceptions = allClasses.stream()
                .filter(ci -> ci.superClass().contains("Exception")
                        || ci.superClass().contains("RuntimeException"))
                .count();
        result.put("errorHandling", Map.of("customExceptions", customExceptions));

        // === Layer chains: detect ${Name}X → ${Name}Y → ${Name}Z patterns ===
        List<Map<String, Object>> layerChains = detectLayerChains(allClasses, classSuffixes);
        if (!layerChains.isEmpty()) {
            result.put("layerChains", layerChains);
        }

        result.put("totalClasses", totalClasses);

        ctx.formatter().printResult(result);
        return 1;
    }

    /**
     * Detects layer chain patterns like Detail→DetailBean→DAO.
     * Finds groups of classes that share a base name with different suffixes.
     */
    private List<Map<String, Object>> detectLayerChains(
            List<com.jsrc.app.parser.model.ClassInfo> allClasses,
            Map<String, Integer> knownSuffixes) {

        // Get suffixes with 3+ occurrences (real patterns, not noise)
        List<String> significantSuffixes = knownSuffixes.entrySet().stream()
                .filter(e -> e.getValue() >= 3)
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .map(Map.Entry::getKey)
                .limit(15)
                .toList();

        if (significantSuffixes.size() < 2) return List.of();

        // For each pair/triple of suffixes, count how many base names have BOTH
        Map<String, Integer> chainCounts = new LinkedHashMap<>();
        Map<String, String> chainExamples = new LinkedHashMap<>();

        // Group classes by extracted base name
        Map<String, List<String>> byBaseName = new HashMap<>();
        for (var ci : allClasses) {
            String suffix = extractSuffix(ci.name());
            if (suffix != null) {
                String baseName = ci.name().substring(0, ci.name().length() - suffix.length());
                byBaseName.computeIfAbsent(baseName, k -> new java.util.ArrayList<>()).add(suffix);
            }
        }

        // Find chains: base names that have 2+ suffixes
        Map<String, Integer> chainPatterns = new HashMap<>();
        Map<String, String> chainPatternExamples = new HashMap<>();

        for (var entry : byBaseName.entrySet()) {
            List<String> suffixes = entry.getValue().stream().sorted().distinct().toList();
            if (suffixes.size() >= 2) {
                String chainKey = String.join(" → ", suffixes.stream()
                        .map(s -> "${Name}" + s).toList());
                chainPatterns.merge(chainKey, 1, Integer::sum);
                chainPatternExamples.putIfAbsent(chainKey,
                        String.join(" → ", suffixes.stream()
                                .map(s -> entry.getKey() + s).toList()));
            }
        }

        // Return chains with 2+ occurrences
        return chainPatterns.entrySet().stream()
                .filter(e -> e.getValue() >= 2)
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(5)
                .map(e -> {
                    Map<String, Object> chain = new LinkedHashMap<>();
                    chain.put("pattern", e.getKey());
                    chain.put("occurrences", e.getValue());
                    chain.put("example", chainPatternExamples.get(e.getKey()));
                    return chain;
                })
                .toList();
    }

    /**
     * Extracts the suffix from a class name by splitting at uppercase boundaries.
     * E.g.: "FacturaDetailBean" → "DetailBean", "OrderService" → "Service",
     * "FacturaDAO" → "DAO", "App" → null (single word).
     */
    /**
     * Extracts the suffix from a class name by finding camelCase word boundaries.
     * <p>
     * Examples: OrderService→Service, FacturaDetailBean→DetailBean,
     * FacturaDAO→DAO, XMLParser→Parser, OAuth2Handler→Handler
     *
     * @return suffix, or null if the name is a single word / trivial
     */
    static String extractSuffix(String className) {
        if (className == null || className.length() <= 1) return null;

        // Find all word boundary positions (where a new "word" starts)
        List<Integer> boundaries = new java.util.ArrayList<>();
        for (int i = 1; i < className.length(); i++) {
            char c = className.charAt(i);
            char prev = className.charAt(i - 1);

            // lowercase→Uppercase: "order|Service"
            if (Character.isUpperCase(c) && Character.isLowerCase(prev)) {
                boundaries.add(i);
            }
            // Uppercase→lowercase after uppercase run: "XML|Parser" (boundary at P)
            else if (Character.isLowerCase(c) && Character.isUpperCase(prev) && i >= 2
                    && Character.isUpperCase(className.charAt(i - 2))) {
                boundaries.add(i - 1);
            }
            // digit→letter or letter→digit: "OAuth|2|Handler"
            else if (Character.isDigit(c) != Character.isDigit(prev)
                    && Character.isUpperCase(c)) {
                boundaries.add(i);
            }
        }

        if (boundaries.isEmpty()) return null;

        // Last word = simple suffix (Service, Parser, DAO, Handler)
        int lastBoundary = boundaries.getLast();
        String simpleSuffix = className.substring(lastBoundary);
        if (simpleSuffix.length() < 2) return null;

        // If the whole name is the suffix, return null
        if (lastBoundary == 0) return null;

        // Try compound suffix: include the word before the last one
        // "FacturaDetailBean" → boundaries at [7=Detail, 13=Bean] → compound = "DetailBean"
        // Don't compound across digits: "OAuth2Handler" → just "Handler"
        if (boundaries.size() >= 2) {
            int secondLast = boundaries.get(boundaries.size() - 2);
            if (secondLast > 0) {
                String between = className.substring(secondLast, lastBoundary);
                // Only compound if the segment between boundaries is pure letters
                if (between.chars().allMatch(Character::isLetter)) {
                    String compound = className.substring(secondLast);
                    if (compound.length() <= 20) {
                        return compound;
                    }
                }
            }
        }

        return simpleSuffix;
    }

    private String percent(int count, int total) {
        if (total == 0) return "0%";
        return Math.round(count * 100.0 / total) + "%";
    }

    private Map<String, Integer> topN(Map<String, Integer> counts, int n) {
        return counts.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(n)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (a, b) -> a, LinkedHashMap::new));
    }
}
