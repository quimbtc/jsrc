package com.jsrc.app.command;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.jsrc.app.output.JsonWriter;
import com.jsrc.app.analysis.CallGraph;
import com.jsrc.app.parser.model.ClassInfo;
import com.jsrc.app.parser.model.MethodReference;

/**
 * Detects dead code: methods nobody calls, classes nobody imports,
 * interfaces without implementors.
 */
public class UnusedCommand implements Command {
    @Override
    public int execute(CommandContext ctx) {
        var allClasses = ctx.getAllClasses();
        var analyzer = ctx.dependencyAnalyzer();

        // Build call graph for method usage
        CallGraph graph = ctx.callGraph();

        // Collect all imported class names
        Set<String> importedClasses = new HashSet<>();
        for (ClassInfo ci : allClasses) {
            var deps = analyzer.analyze(ctx.javaFiles(), ci.name());
            if (deps == null) continue;
            for (String imp : deps.imports()) {
                int lastDot = imp.lastIndexOf('.');
                if (lastDot > 0) importedClasses.add(imp.substring(lastDot + 1));
            }
            for (var field : deps.fieldDependencies()) importedClasses.add(field.type());
            for (var ctor : deps.constructorDependencies()) importedClasses.add(ctor.type());
        }

        // Find unused methods (no callers, not main, not constructors)
        List<Map<String, Object>> unusedMethods = new ArrayList<>();
        for (MethodReference ref : graph.getAllMethods()) {
            if (ref.methodName().equals("main") || ref.methodName().equals("<init>")) continue;
            if (graph.isRoot(ref)) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("className", ref.className());
                entry.put("methodName", ref.methodName());
                unusedMethods.add(entry);
            }
        }

        // Find unused classes (nobody imports them)
        List<String> unusedClasses = allClasses.stream()
                .filter(ci -> !ci.name().equals("App")) // skip entry point
                .filter(ci -> !importedClasses.contains(ci.name()))
                .map(ClassInfo::qualifiedName)
                .toList();

        // Find interfaces without implementors
        List<String> unimplemented = allClasses.stream()
                .filter(ClassInfo::isInterface)
                .filter(iface -> allClasses.stream().noneMatch(ci ->
                        ci.interfaces().contains(iface.name())
                                || ci.interfaces().contains(iface.qualifiedName())))
                .map(ClassInfo::qualifiedName)
                .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("unusedMethods", unusedMethods);
        result.put("unusedClasses", unusedClasses);
        result.put("unimplementedInterfaces", unimplemented);
        result.put("total", unusedMethods.size() + unusedClasses.size() + unimplemented.size());

        ctx.formatter().printResult(result);
        return unusedMethods.size() + unusedClasses.size() + unimplemented.size();
    }
}
