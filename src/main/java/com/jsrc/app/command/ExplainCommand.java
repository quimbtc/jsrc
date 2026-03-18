package com.jsrc.app.command;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.jsrc.app.architecture.LayerResolver;
import com.jsrc.app.output.JsonWriter;
import com.jsrc.app.analysis.CallGraph;
import com.jsrc.app.parser.model.ClassInfo;

/**
 * Generates a concise, actionable summary of a class.
 * One paragraph the agent can use directly in its reasoning.
 */
public class ExplainCommand implements Command {
    private final String className;

    public ExplainCommand(String className) {
        this.className = className;
    }

    @Override
    public int execute(CommandContext ctx) {
        var allClasses = ctx.getAllClasses();
        ClassInfo ci = SummaryCommand.resolveOrExit(allClasses, className);
        if (ci == null) return 0;

        StringBuilder summary = new StringBuilder();
        String kind = ci.isInterface() ? "Interface" : ci.isAbstract() ? "Abstract class" : "Class";
        summary.append(kind).append(" in ").append(ci.packageName()).append(". ");
        summary.append(ci.methods().size()).append(" method(s). ");

        // Hierarchy
        if (!ci.superClass().isEmpty()) {
            summary.append("Extends ").append(ci.superClass()).append(". ");
        }
        if (!ci.interfaces().isEmpty()) {
            summary.append("Implements ").append(String.join(", ", ci.interfaces())).append(". ");
        }

        // Dependencies
        var analyzer = ctx.dependencyAnalyzer();
        var deps = analyzer.analyze(ctx.javaFiles(), ci.name());
        if (deps != null) {
            int fieldCount = deps.fieldDependencies().size();
            int ctorCount = deps.constructorDependencies().size();
            if (ctorCount > 0) summary.append(ctorCount).append(" constructor-injected dep(s). ");
            else if (fieldCount > 0) summary.append(fieldCount).append(" field dep(s). ");
        }

        // Callers
        CallGraph graph = ctx.callGraph();
        long callerCount = ci.methods().stream()
                .flatMap(m -> graph.findMethodsByName(m.name()).stream())
                .flatMap(ref -> graph.getCallersOf(ref).stream())
                .map(call -> call.caller().className())
                .distinct()
                .filter(name -> !name.equals(ci.name()))
                .count();
        if (callerCount > 0) summary.append("Called by ").append(callerCount).append(" class(es). ");

        // Layer
        if (ctx.config() != null && !ctx.config().architecture().layers().isEmpty()) {
            var resolver = new LayerResolver(ctx.config().architecture().layers());
            String layer = resolver.resolve(ci);
            if (layer != null) summary.append("Layer: ").append(layer).append(". ");
        }

        // Smells
        long smellCount = ctx.javaFiles().stream()
                .flatMap(f -> ctx.parser().detectSmells(f).stream())
                .filter(s -> s.className().equals(ci.name()))
                .count();
        if (smellCount > 0) summary.append(smellCount).append(" code smell(s). ");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("className", ci.qualifiedName());
        result.put("summary", summary.toString().trim());

        ctx.formatter().printResult(result);
        return 1;
    }
}
