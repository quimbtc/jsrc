package com.jsrc.app.command;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.jsrc.app.analysis.CallGraph;
import com.jsrc.app.parser.model.ClassInfo;

/**
 * Shows classes related to a target, ranked by coupling score.
 * Helps agents focus on the right files without reading the entire codebase.
 */
public class RelatedCommand implements Command {

    private final String className;

    public RelatedCommand(String className) {
        this.className = className;
    }

    @Override
    public int execute(CommandContext ctx) {
        var allClasses = ctx.getAllClasses();
        ClassInfo target = allClasses.stream()
                .filter(c -> c.name().equals(className) || c.qualifiedName().equals(className))
                .findFirst().orElse(null);

        if (target == null) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", "Class not found: " + className);
            ctx.formatter().printResult(error);
            return 0;
        }

        CallGraph graph = ctx.callGraph();
        Map<String, Integer> scores = new LinkedHashMap<>();

        // Direct deps: field types + constructor param types
        Set<String> directDeps = new LinkedHashSet<>();
        for (var field : target.fields()) {
            String type = field.type();
            if (!type.equals(target.name()) && !isPrimitive(type)) {
                directDeps.add(type);
                scores.merge(type, 3, Integer::sum); // field = +3
            }
        }

        // Callers: classes that call any method of target
        Set<String> callers = new LinkedHashSet<>();
        for (var method : target.methods()) {
            for (var ref : graph.findMethodsByName(method.name())) {
                if (!ref.className().equals(target.name())) continue;
                for (var call : graph.getCallersOf(ref)) {
                    String caller = call.caller().className();
                    if (!caller.equals(target.name()) && !"?".equals(caller)) {
                        callers.add(caller);
                        scores.merge(caller, 2, Integer::sum); // caller = +2
                    }
                }
            }
        }

        // Callees: classes that target calls
        for (var method : target.methods()) {
            for (var ref : graph.findMethodsByName(method.name())) {
                if (!ref.className().equals(target.name())) continue;
                for (var call : graph.getCalleesOf(ref)) {
                    String callee = call.callee().className();
                    if (!callee.equals(target.name()) && !"?".equals(callee)) {
                        scores.merge(callee, 2, Integer::sum); // callee = +2
                        directDeps.add(callee);
                    }
                }
            }
        }

        // Same package
        List<String> samePackage = allClasses.stream()
                .filter(c -> !c.name().equals(target.name()))
                .filter(c -> c.packageName().equals(target.packageName()))
                .map(ClassInfo::name)
                .toList();
        for (String sp : samePackage) {
            scores.merge(sp, 1, Integer::sum); // same package = +1
        }

        // Build ranked list
        List<Map<String, Object>> ranked = scores.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<String, Integer> e) -> -e.getValue())
                        .thenComparing(Map.Entry::getKey))
                .map(e -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("name", e.getKey());
                    item.put("score", e.getValue());
                    List<String> reasons = new ArrayList<>();
                    if (directDeps.contains(e.getKey())) reasons.add("dep");
                    if (callers.contains(e.getKey())) reasons.add("caller");
                    if (samePackage.contains(e.getKey())) reasons.add("samePackage");
                    item.put("reasons", reasons);
                    return item;
                })
                .limit(15)
                .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("target", target.qualifiedName());
        result.put("directDeps", directDeps.stream().filter(d -> !d.equals(target.name())).toList());
        result.put("callers", callers.stream().toList());
        result.put("samePackage", samePackage);
        result.put("ranked", ranked);

        ctx.formatter().printResult(result);
        return ranked.size();
    }

    private static boolean isPrimitive(String type) {
        return Set.of("int", "long", "short", "byte", "float", "double", "boolean", "char", "String")
                .contains(type);
    }
}
