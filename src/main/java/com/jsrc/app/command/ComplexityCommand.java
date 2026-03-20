package com.jsrc.app.command;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.jsrc.app.analysis.CallGraph;
import com.jsrc.app.parser.model.ClassInfo;
import com.jsrc.app.parser.model.MethodInfo;
import com.jsrc.app.util.MethodResolver;

/**
 * Estimates complexity: cyclomatic complexity approximation, fan-out, fan-in, LOC.
 * Used by orchestrators to estimate effort before assigning work.
 */
public class ComplexityCommand implements Command {

    private final String input;

    public ComplexityCommand(String input) {
        this.input = input;
    }

    @Override
    public int execute(CommandContext ctx) {
        var ref = MethodResolver.parse(input);
        var allClasses = ctx.getAllClasses();
        CallGraph graph = ctx.callGraph();

        // Find target class
        String className = ref.hasClassName() ? ref.className() : ref.methodName();
        ClassInfo target = allClasses.stream()
                .filter(ci -> ci.name().equals(className) || ci.qualifiedName().equals(className))
                .findFirst().orElse(null);

        if (target == null) {
            ctx.formatter().printResult(Map.of("error", "Class not found: " + input));
            return 0;
        }

        // Per-method metrics
        List<Map<String, Object>> methodMetrics = new ArrayList<>();
        int totalLoc = 0, totalFanOut = 0, totalFanIn = 0;

        for (MethodInfo m : target.methods()) {
            int loc = m.endLine() - m.startLine() + 1;
            totalLoc += loc;

            // Fan-out: distinct methods this method calls
            int fanOut = 0;
            for (var mref : graph.findMethodsByName(m.name())) {
                if (mref.className().equals(target.name())) {
                    fanOut = (int) graph.getCalleesOf(mref).stream()
                            .map(c -> c.callee().className() + "." + c.callee().methodName())
                            .distinct().count();
                    break;
                }
            }
            totalFanOut += fanOut;

            // Fan-in: distinct callers
            int fanIn = 0;
            for (var mref : graph.findMethodsByName(m.name())) {
                if (mref.className().equals(target.name())) {
                    fanIn = (int) graph.getCallersOf(mref).stream()
                            .map(c -> c.caller().className() + "." + c.caller().methodName())
                            .distinct().count();
                    break;
                }
            }
            totalFanIn += fanIn;

            // Approximate cyclomatic complexity from LOC (heuristic: 1 branch per 5 LOC)
            int complexity = Math.max(1, loc / 5);

            Map<String, Object> mm = new LinkedHashMap<>();
            mm.put("method", m.name());
            mm.put("loc", loc);
            mm.put("complexity", complexity);
            mm.put("fanOut", fanOut);
            mm.put("fanIn", fanIn);
            methodMetrics.add(mm);
        }

        // Class-level metrics
        int avgComplexity = target.methods().isEmpty() ? 0
                : methodMetrics.stream().mapToInt(m -> ((Number) m.get("complexity")).intValue()).sum() / target.methods().size();

        String couplingScore = totalFanOut > 30 ? "high" : totalFanOut > 10 ? "medium" : "low";
        String effort;
        if (avgComplexity <= 3 && totalFanOut <= 10) effort = "low (< 1 hour)";
        else if (avgComplexity <= 8 && totalFanOut <= 25) effort = "medium (2-4 hours)";
        else effort = "high (4+ hours)";

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("target", ctx.qualify(target.name()));
        result.put("totalMethods", target.methods().size());
        result.put("totalLoc", totalLoc);
        result.put("avgComplexity", avgComplexity);
        result.put("totalFanOut", totalFanOut);
        result.put("totalFanIn", totalFanIn);
        result.put("couplingScore", couplingScore);
        result.put("estimatedEffort", effort);
        result.put("methods", methodMetrics);

        ctx.formatter().printResult(result);
        return target.methods().size();
    }
}
