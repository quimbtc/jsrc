package com.jsrc.app.command;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.jsrc.app.parser.model.MethodReference;
import com.jsrc.app.util.MethodResolver;
import com.jsrc.app.util.SignatureUtils;

/**
 * Validates whether a method reference exists in the codebase.
 * Anti-hallucination tool for AI agents: verify before generating calls.
 * <p>
 * If the method doesn't exist, suggests the closest match using:
 * <ul>
 *   <li>Levenshtein distance on method name</li>
 *   <li>Prefix/substring matching</li>
 *   <li>Same method name in different class</li>
 *   <li>Overloads with different param count</li>
 * </ul>
 */
public class ValidateCommand implements Command {

    private final String methodInput;

    public ValidateCommand(String methodInput) {
        this.methodInput = methodInput;
    }

    @Override
    public int execute(CommandContext ctx) {
        var ref = MethodResolver.parse(methodInput);
        String methodName = ref.methodName();
        var graph = ctx.callGraph();

        // Find all methods with this name
        Set<MethodReference> byName = graph.findMethodsByName(methodName);

        // Also search index for richer info (signatures)
        Map<String, String> signatures = new LinkedHashMap<>();
        if (ctx.indexed() != null) {
            for (var entry : ctx.indexed().getEntries()) {
                for (var ic : entry.classes()) {
                    for (var im : ic.methods()) {
                        signatures.put(ic.name() + "." + im.name(), im.signature());
                    }
                }
            }
        }

        // Filter by class if specified
        if (ref.hasClassName()) {
            var classMatch = byName.stream()
                    .filter(m -> m.className().equals(ref.className()))
                    .collect(Collectors.toSet());

            if (!classMatch.isEmpty()) {
                // Filter by param count if specified
                if (ref.hasParamTypes()) {
                    var paramMatch = classMatch.stream()
                            .filter(m -> m.parameterCount() < 0 || m.parameterCount() == ref.paramTypes().size())
                            .collect(Collectors.toSet());
                    if (!paramMatch.isEmpty()) {
                        return reportValid(ctx, paramMatch.iterator().next(), signatures);
                    }
                    // Wrong param count — suggest overloads
                    return reportInvalid(ctx, ref, "Parameter count mismatch",
                            suggestOverloads(classMatch, signatures));
                }
                return reportValid(ctx, classMatch.iterator().next(), signatures);
            }

            // Method not in this class — fuzzy search
            return reportInvalid(ctx, ref, "Method not found in " + ref.className(),
                    findClosest(ref, graph.getAllMethods(), signatures));
        }

        // No class specified
        if (byName.size() == 1) {
            return reportValid(ctx, byName.iterator().next(), signatures);
        }
        if (byName.size() > 1) {
            // Ambiguous
            return reportAmbiguous(ctx, ref, byName, signatures);
        }

        // Not found — fuzzy search across all methods
        return reportInvalid(ctx, ref, "Method not found",
                findClosest(ref, graph.getAllMethods(), signatures));
    }

    private int reportValid(CommandContext ctx, MethodReference match,
                             Map<String, String> signatures) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("valid", true);
        result.put("className", ctx.qualify(match.className()));
        result.put("methodName", match.methodName());
        String sig = signatures.get(match.className() + "." + match.methodName());
        if (sig != null) result.put("signature", sig);
        ctx.formatter().printResult(result);
        return 1;
    }

    private int reportInvalid(CommandContext ctx, MethodResolver.MethodRef ref,
                               String reason, List<String> closest) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("valid", false);
        result.put("reason", reason);
        result.put("target", ref.hasClassName()
                ? ref.className() + "." + ref.methodName() : ref.methodName());
        if (!closest.isEmpty()) {
            result.put("closest", closest);
        }
        ctx.formatter().printResult(result);
        return 0;
    }

    private int reportAmbiguous(CommandContext ctx, MethodResolver.MethodRef ref,
                                 Set<MethodReference> matches,
                                 Map<String, String> signatures) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("valid", true);
        result.put("ambiguous", true);
        result.put("methodName", ref.methodName());
        List<String> candidates = matches.stream()
                .map(m -> {
                    String sig = signatures.get(m.className() + "." + m.methodName());
                    return sig != null ? m.className() + "." + sig : m.className() + "." + m.methodName() + "()";
                })
                .sorted().distinct().toList();
        result.put("candidates", candidates);
        result.put("message", "Method exists in multiple classes. Use Class.method to disambiguate.");
        ctx.formatter().printResult(result);
        return 1;
    }

    private List<String> suggestOverloads(Set<MethodReference> classMatches,
                                           Map<String, String> signatures) {
        return classMatches.stream()
                .map(m -> {
                    String sig = signatures.get(m.className() + "." + m.methodName());
                    return sig != null ? m.className() + "." + sig : m.className() + "." + m.methodName() + "()";
                })
                .sorted().distinct().toList();
    }

    private List<String> findClosest(MethodResolver.MethodRef ref,
                                      Set<MethodReference> allMethods,
                                      Map<String, String> signatures) {
        String target = ref.methodName();
        String targetClass = ref.hasClassName() ? ref.className() : null;
        List<ScoredMatch> scored = new ArrayList<>();

        for (MethodReference m : allMethods) {
            if (m.methodName().equals("<init>")) continue;

            int score = 0;
            String name = m.methodName();

            // Exact name in different class
            if (name.equals(target) && targetClass != null && !m.className().equals(targetClass)) {
                score = 100;
            }
            // Levenshtein distance
            int dist = levenshtein(target.toLowerCase(), name.toLowerCase());
            if (dist <= 3 && dist > 0) {
                score = Math.max(score, 80 - dist * 10);
            }
            // Prefix match
            if (name.toLowerCase().startsWith(target.toLowerCase()) || target.toLowerCase().startsWith(name.toLowerCase())) {
                score = Math.max(score, 60);
            }
            // Substring match
            if (name.toLowerCase().contains(target.toLowerCase()) || target.toLowerCase().contains(name.toLowerCase())) {
                score = Math.max(score, 40);
            }

            if (score > 0) {
                String sig = signatures.get(m.className() + "." + m.methodName());
                String display = sig != null ? m.className() + "." + sig : m.className() + "." + name + "()";
                scored.add(new ScoredMatch(display, score));
            }
        }

        return scored.stream()
                .sorted((a, b) -> Integer.compare(b.score, a.score))
                .map(s -> s.display)
                .distinct()
                .limit(5)
                .toList();
    }

    private record ScoredMatch(String display, int score) {}

    static int levenshtein(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) dp[i][0] = i;
        for (int j = 0; j <= b.length(); j++) dp[0][j] = j;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(dp[i - 1][j] + 1,
                        Math.min(dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost));
            }
        }
        return dp[a.length()][b.length()];
    }
}
