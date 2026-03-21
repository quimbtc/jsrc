package com.jsrc.app.command;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import com.jsrc.app.analysis.CallGraph;
import com.jsrc.app.parser.model.MethodCall;
import com.jsrc.app.parser.model.MethodReference;
import com.jsrc.app.util.MethodResolver;
import com.jsrc.app.util.MethodTargetResolver;

/**
 * Pre-computes the impact of changing a method: who calls it (directly and
 * transitively), risk level, and affected classes.
 * Helps agents understand what breaks before making changes.
 */
public class ImpactCommand implements Command {

    private static final int MAX_DEPTH = 30;

    private final String methodInput;

    public ImpactCommand(String methodInput) {
        this.methodInput = methodInput;
    }

    @Override
    public int execute(CommandContext ctx) {
        var ref = MethodResolver.parse(methodInput);
        CallGraph graph = ctx.callGraph();
        var resolved = MethodTargetResolver.resolve(ref, graph);

        if (resolved.targets().isEmpty()) {
            // Fallback: count usages of method name in code (without printing search results)
            String searchTerm = ref.methodName();
            long usageCount = 0;
            for (var file : ctx.javaFiles()) {
                try {
                    for (String line : java.nio.file.Files.readAllLines(file)) {
                        if (line.contains(searchTerm)) usageCount++;
                    }
                } catch (Exception e) { /* skip */ }
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("target", methodInput);
            if (usageCount > 0) {
                result.put("error", "Method not in call graph (class may be external)");
                result.put("textUsages", usageCount);
                result.put("hint", "Use --search '" + searchTerm + "' for detailed locations");
            } else {
                result.put("error", "Method not found");
            }
            String closest = SummaryCommand.findClosestClass(ctx.getAllClasses(),
                    ref.hasClassName() ? ref.className() : ref.methodName());
            if (closest != null) result.put("suggestion", closest);
            ctx.formatter().printResult(result);
            return 0;
        }

        // Direct callers
        Set<String> directCallerClasses = new LinkedHashSet<>();
        for (var target : resolved.targets()) {
            for (MethodCall call : graph.getCallersOf(target)) {
                String caller = call.caller().className();
                if (!"?".equals(caller)) directCallerClasses.add(ctx.qualify(caller));
            }
        }

        // Transitive callers via BFS
        Set<String> allAffected = new LinkedHashSet<>(directCallerClasses);
        Set<MethodReference> visited = new HashSet<>(resolved.targets());
        Queue<MethodReference> queue = new LinkedList<>();
        for (var target : resolved.targets()) {
            for (MethodCall call : graph.getCallersOf(target)) {
                if (visited.add(call.caller())) queue.add(call.caller());
            }
        }

        int depth = 0;
        while (!queue.isEmpty() && depth < MAX_DEPTH) {
            int levelSize = queue.size();
            for (int i = 0; i < levelSize; i++) {
                MethodReference current = queue.poll();
                if (!"?".equals(current.className())) {
                    allAffected.add(ctx.qualify(current.className()));
                }
                for (MethodCall call : graph.getCallersOf(current)) {
                    if (visited.add(call.caller())) {
                        queue.add(call.caller());
                    }
                }
            }
            depth++;
        }

        // Risk level
        int directCount = directCallerClasses.size();
        String risk;
        if (directCount == 0) risk = "none";
        else if (directCount <= 3) risk = "low";
        else if (directCount <= 10) risk = "medium";
        else risk = "high";

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("target", methodInput);
        result.put("directCallers", directCount);
        result.put("transitiveCallers", allAffected.size());
        result.put("affectedClasses", allAffected.stream().sorted().toList());
        result.put("riskLevel", risk);

        if (ctx.mdOutput()) {
            String md = toMarkdown(methodInput, directCount, allAffected, risk);
            com.jsrc.app.output.MarkdownWriter.output(md, ctx.outDir(), "impact-" + methodInput.replace(".", "-"));
            return allAffected.size();
        }

        ctx.formatter().printResult(result);
        return allAffected.size();
    }

    private String toMarkdown(String target, int direct, java.util.Set<String> affected, String risk) {
        String badge = switch (risk) {
            case "high" -> "🔴 HIGH";
            case "medium" -> "🟡 MEDIUM";
            case "low" -> "🟢 LOW";
            default -> "⚪ NONE";
        };
        var sb = new StringBuilder();
        sb.append("# Impact Analysis: `").append(target).append("`\n\n");
        sb.append("**Risk Level:** ").append(badge).append("\n\n");
        sb.append("| Metric | Value |\n|--------|-------|\n");
        sb.append("| Direct callers | ").append(direct).append(" |\n");
        sb.append("| Transitive callers | ").append(affected.size()).append(" |\n\n");
        if (!affected.isEmpty()) {
            sb.append("## Affected Classes\n\n");
            for (String cls : affected) sb.append("- `").append(cls).append("`\n");
        }
        return sb.toString();
    }
}
