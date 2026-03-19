package com.jsrc.app.command;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.jsrc.app.analysis.CallGraph;
import com.jsrc.app.parser.model.ClassInfo;
import com.jsrc.app.parser.model.FieldInfo;

/**
 * Pre-compile diagnostics from the index: unknown types, dead code, style inconsistencies.
 * Catches common agent mistakes before running mvn compile.
 */
public class LintCommand implements Command {

    private static final Set<String> JDK_TYPES = Set.of(
            "String", "Object", "Integer", "Long", "Double", "Float", "Boolean",
            "Byte", "Short", "Character", "List", "Map", "Set", "Collection",
            "Optional", "Stream", "Iterable", "Comparable", "Serializable",
            "Runnable", "Callable", "Future", "CompletableFuture",
            "Path", "File", "IOException", "Exception", "RuntimeException",
            "Class", "Enum", "Record", "Annotation");

    private static final Set<String> PRIMITIVES = Set.of(
            "int", "long", "short", "byte", "float", "double", "boolean", "char", "void");

    private final String className;

    public LintCommand(String className) {
        this.className = className;
    }

    @Override
    public int execute(CommandContext ctx) {
        var allClasses = ctx.getAllClasses();
        ClassInfo target = allClasses.stream()
                .filter(c -> c.name().equals(className) || c.qualifiedName().equals(className))
                .findFirst().orElse(null);

        if (target == null) {
            ctx.formatter().printResult(Map.of("error", "Class not found: " + className));
            return 0;
        }

        Set<String> knownTypes = new java.util.HashSet<>(JDK_TYPES);
        knownTypes.addAll(PRIMITIVES);
        for (var ci : allClasses) knownTypes.add(ci.name());

        List<Map<String, Object>> diagnostics = new ArrayList<>();

        // Check field types exist
        for (FieldInfo f : target.fields()) {
            String type = f.type();
            if (!knownTypes.contains(type) && !type.contains(".") && !type.contains("[]")) {
                diagnostics.add(diag("warning", 0,
                        "Unknown type '" + type + "' for field '" + f.name() + "' — not in index or JDK"));
            }
        }

        // Check for dead code (private-like methods with 0 callers)
        CallGraph graph = ctx.callGraph();
        for (var m : target.methods()) {
            if (m.name().equals(target.name())) continue; // skip constructors
            if (m.name().equals("main") || m.name().equals("toString")
                    || m.name().equals("hashCode") || m.name().equals("equals")) continue;

            var refs = graph.findMethodsByName(m.name()).stream()
                    .filter(r -> r.className().equals(target.name()))
                    .toList();
            boolean hasCaller = refs.stream()
                    .anyMatch(r -> !graph.getCallersOf(r).isEmpty());

            // Only report private-ish methods (those not called from outside)
            if (!hasCaller && m.signature() != null && !m.signature().contains("public")) {
                diagnostics.add(diag("info", m.startLine(),
                        "Method " + m.name() + "() has no callers — potential dead code"));
            }
        }

        ctx.formatter().printResult(diagnostics);
        return diagnostics.size();
    }

    private Map<String, Object> diag(String severity, int line, String message) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("severity", severity);
        if (line > 0) d.put("line", line);
        d.put("message", message);
        return d;
    }
}
