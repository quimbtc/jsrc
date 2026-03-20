package com.jsrc.app.command;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.jsrc.app.analysis.CallGraph;
import com.jsrc.app.parser.model.ClassInfo;
import com.jsrc.app.parser.model.MethodInfo;

/**
 * Generates a token-budget-aware repo map.
 * Ranks classes by importance (caller count) and truncates to fit budget.
 * Designed to give an agent a global mental model of the codebase.
 */
public class MapCommand implements Command {

    private static final int DEFAULT_BUDGET = 2000;
    private static final int MAX_METHODS_PER_CLASS = 3;

    private final int budgetTokens;

    public MapCommand(int budgetTokens) {
        this.budgetTokens = budgetTokens > 0 ? budgetTokens : DEFAULT_BUDGET;
    }

    public MapCommand() {
        this(DEFAULT_BUDGET);
    }

    @Override
    public int execute(CommandContext ctx) {
        var allClasses = ctx.getAllClasses();
        if (allClasses.isEmpty()) {
            ctx.formatter().printResult(Map.of("map", List.of(), "included", 0, "totalClasses", 0));
            return 0;
        }

        CallGraph graph = ctx.callGraph();

        // Score each class by external callers
        List<ScoredClass> scored = allClasses.stream()
                .map(ci -> new ScoredClass(ci, countExternalCallers(ci, graph)))
                .sorted(Comparator.comparingInt((ScoredClass s) -> -s.calledBy)
                        .thenComparing(s -> s.ci.name()))
                .toList();

        // Build map entries within budget
        var mapEntries = new java.util.ArrayList<Map<String, Object>>();
        int usedTokens = 0;
        int budgetChars = budgetTokens * 4; // ~4 chars per token

        for (ScoredClass sc : scored) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("class", sc.ci.qualifiedName());
            entry.put("pkg", MiniCommand.abbreviatePackage(sc.ci.packageName()));
            entry.put("kind", sc.ci.isInterface() ? "interface" : "class");

            // Top methods by signature length (shorter = more informative in compact form)
            List<String> methods = sc.ci.methods().stream()
                    .filter(m -> !m.name().equals(sc.ci.name())) // skip constructors
                    .sorted(Comparator.comparingInt((MethodInfo m) -> m.signature() != null ? m.signature().length() : 999))
                    .limit(MAX_METHODS_PER_CLASS)
                    .map(m -> m.signature() != null ? m.signature() : m.name() + "()")
                    .toList();
            entry.put("methods", methods);
            entry.put("calledBy", sc.calledBy);

            // Estimate chars for this entry
            int entryChars = estimateChars(entry);

            if (usedTokens + entryChars / 4 > budgetTokens && !mapEntries.isEmpty()) {
                break; // Budget exceeded, stop (but always include at least 1)
            }

            mapEntries.add(entry);
            usedTokens += entryChars / 4;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("map", mapEntries);
        result.put("included", mapEntries.size());
        result.put("totalClasses", allClasses.size());
        if (mapEntries.size() < allClasses.size()) {
            result.put("truncatedAt", budgetTokens);
        }

        ctx.formatter().printResult(result);
        return mapEntries.size();
    }

    private int countExternalCallers(ClassInfo ci, CallGraph graph) {
        return (int) ci.methods().stream()
                .flatMap(m -> graph.findMethodsByName(m.name()).stream())
                .filter(ref -> ref.className().equals(ci.name()))
                .flatMap(ref -> graph.getCallersOf(ref).stream())
                .map(call -> call.caller().className())
                .filter(name -> !name.equals(ci.name()))
                .distinct()
                .count();
    }

    private int estimateChars(Map<String, Object> entry) {
        // Rough: class name + pkg + kind + methods + calledBy overhead
        int chars = 20; // JSON overhead
        chars += entry.get("class").toString().length();
        chars += entry.get("pkg").toString().length();
        @SuppressWarnings("unchecked")
        var methods = (List<String>) entry.get("methods");
        for (String m : methods) chars += m.length() + 5;
        return chars;
    }

    private record ScoredClass(ClassInfo ci, int calledBy) {}
}
