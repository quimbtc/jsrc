package com.jsrc.app.command;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.jsrc.app.parser.model.ClassInfo;

/**
 * Identifies relevant classes for a task based on keyword matching.
 * Helps agents plan which files to read before making changes.
 * <p>
 * Searches class names, method names, field names, and package names.
 * Returns ranked results with estimated token cost.
 */
public class ScopeCommand implements Command {

    private final String query;

    public ScopeCommand(String query) {
        this.query = query;
    }

    @Override
    public int execute(CommandContext ctx) {
        String[] keywords = query.toLowerCase().split("[\\s,]+");
        var allClasses = ctx.getAllClasses();

        List<ScoredClass> scored = new ArrayList<>();
        for (ClassInfo ci : allClasses) {
            int score = 0;
            List<String> matches = new ArrayList<>();

            for (String kw : keywords) {
                // Class name match (+5)
                if (ci.name().toLowerCase().contains(kw)) {
                    score += 5;
                    matches.add("class name");
                }
                // Method name match (+3 per method)
                long methodMatches = ci.methods().stream()
                        .filter(m -> m.name().toLowerCase().contains(kw))
                        .count();
                if (methodMatches > 0) {
                    score += (int) (methodMatches * 3);
                    matches.add(methodMatches + " method(s)");
                }
                // Field name/type match (+2)
                long fieldMatches = ci.fields().stream()
                        .filter(f -> f.name().toLowerCase().contains(kw)
                                || f.type().toLowerCase().contains(kw))
                        .count();
                if (fieldMatches > 0) {
                    score += (int) (fieldMatches * 2);
                    matches.add(fieldMatches + " field(s)");
                }
                // Package match (+1)
                if (ci.packageName().toLowerCase().contains(kw)) {
                    score += 1;
                    matches.add("package");
                }
            }

            if (score > 0) {
                scored.add(new ScoredClass(ci, score, matches));
            }
        }

        if (scored.isEmpty()) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("query", query);
            result.put("matchedClasses", List.of());
            result.put("message", "No classes match the query keywords.");
            ctx.formatter().printResult(result);
            return 0;
        }

        scored.sort(Comparator.comparingInt((ScoredClass s) -> -s.score)
                .thenComparing(s -> s.ci.name()));

        List<Map<String, Object>> matchedClasses = scored.stream()
                .limit(15)
                .map(s -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", s.ci.qualifiedName());
                    m.put("score", s.score);
                    m.put("matches", s.matches);
                    return m;
                })
                .toList();

        // Top methods matching keywords
        List<String> topMethods = new ArrayList<>();
        for (var s : scored.subList(0, Math.min(5, scored.size()))) {
            for (var method : s.ci.methods()) {
                for (String kw : keywords) {
                    if (method.name().toLowerCase().contains(kw)) {
                        topMethods.add(s.ci.name() + "." + method.name());
                    }
                }
            }
        }

        // Estimated tokens for top-10 summaries
        int estimatedChars = scored.stream()
                .limit(10)
                .mapToInt(s -> Math.max(200, (s.ci.endLine() - s.ci.startLine()) * 5))
                .sum();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", query);
        result.put("matchedClasses", matchedClasses);
        result.put("topMethods", topMethods.stream().distinct().limit(10).toList());
        result.put("estimatedTokens", estimatedChars / 4);
        result.put("totalMatches", scored.size());

        ctx.formatter().printResult(result);
        return scored.size();
    }

    private record ScoredClass(ClassInfo ci, int score, List<String> matches) {}
}
