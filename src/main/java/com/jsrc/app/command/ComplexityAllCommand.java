package com.jsrc.app.command;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.jsrc.app.parser.model.ClassInfo;
import com.jsrc.app.parser.model.MethodInfo;

/**
 * Scans the entire codebase and returns the top methods by estimated complexity.
 * Supports --no-test to exclude test classes.
 */
public class ComplexityAllCommand implements Command {

    private static final int TOP_N = 30;

    @Override
    public int execute(CommandContext ctx) {
        List<Map<String, Object>> allMethods = new ArrayList<>();

        for (ClassInfo ci : ctx.getAllClasses()) {
            for (MethodInfo m : ci.methods()) {
                int loc = m.endLine() - m.startLine() + 1;
                if (loc <= 0) continue;
                int complexity = Math.max(1, loc / 5);

                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("class", ci.qualifiedName());
                entry.put("method", m.name());
                entry.put("loc", loc);
                entry.put("complexity", complexity);
                entry.put("signature", m.signature());
                allMethods.add(entry);
            }
        }

        // Sort by complexity descending, then by LOC descending
        allMethods.sort(Comparator.<Map<String, Object>, Integer>comparing(
                m -> ((Number) m.get("complexity")).intValue()).reversed()
                .thenComparing(Comparator.<Map<String, Object>, Integer>comparing(
                        m -> ((Number) m.get("loc")).intValue()).reversed()));

        List<Map<String, Object>> top = allMethods.subList(0, Math.min(TOP_N, allMethods.size()));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalMethodsAnalyzed", allMethods.size());
        result.put("top", top);

        ctx.formatter().printResult(result);
        return top.size();
    }
}
