package com.jsrc.app.command;

import java.util.LinkedHashMap;
import java.util.Map;

import com.jsrc.app.output.JsonWriter;
import com.jsrc.app.parser.DependencyAnalyzer;
import com.jsrc.app.parser.model.ClassInfo;
import com.jsrc.app.parser.model.MethodInfo;

/**
 * Computes code metrics for a class: LOC, methods, complexity, coupling.
 */
public class MetricsCommand implements Command {
    private final String className;

    public MetricsCommand(String className) {
        this.className = className;
    }

    @Override
    public int execute(CommandContext ctx) {
        var allClasses = ctx.getAllClasses();
        ClassInfo ci = SummaryCommand.resolveOrExit(allClasses, className);
        if (ci == null) return 0;

        int loc = ci.endLine() - ci.startLine() + 1;
        int methodCount = ci.methods().size();

        // Approximate cyclomatic complexity: count branches in method signatures/names
        // Real complexity needs AST — use method count + throws as proxy
        int complexity = 0;
        for (MethodInfo m : ci.methods()) {
            int methodLines = m.endLine() - m.startLine() + 1;
            // Rough estimate: 1 base + 1 per 10 lines
            complexity += 1 + (methodLines / 10);
        }

        // Coupling: count unique dependencies
        int coupling = 0;
        var analyzer = new DependencyAnalyzer();
        var deps = analyzer.analyze(ctx.javaFiles(), ci.name());
        if (deps != null) {
            coupling = deps.fieldDependencies().size() + deps.constructorDependencies().size();
        }

        // Depth of inheritance
        int depth = 0;
        String current = ci.superClass();
        while (!current.isEmpty() && !current.equals("Object")) {
            depth++;
            String finalCurrent = current;
            current = allClasses.stream()
                    .filter(c -> c.name().equals(finalCurrent) || c.qualifiedName().equals(finalCurrent))
                    .map(ClassInfo::superClass)
                    .findFirst().orElse("");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("className", ci.qualifiedName());
        result.put("loc", loc);
        result.put("methods", methodCount);
        result.put("complexity", complexity);
        result.put("coupling", coupling);
        result.put("inheritanceDepth", depth);
        result.put("isInterface", ci.isInterface());
        result.put("isAbstract", ci.isAbstract());

        System.out.println(JsonWriter.toJson(result));
        return 1;
    }
}
