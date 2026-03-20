package com.jsrc.app.command;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.jsrc.app.analysis.CallGraph;
import com.jsrc.app.parser.model.ClassInfo;
import com.jsrc.app.parser.model.FieldInfo;
import com.jsrc.app.parser.model.MethodInfo;

/**
 * Ultra-compact class summary (&lt;500 chars) for models with small context windows.
 * Shows only the most important methods (by caller count), key fields, and deps.
 */
public class MiniCommand implements Command {

    private static final int MAX_METHODS = 5;
    private static final int MAX_FIELDS = 3;
    private static final int MAX_DEPS = 5;
    private static final Set<String> PRIMITIVE_TYPES = Set.of(
            "int", "long", "short", "byte", "float", "double", "boolean", "char");

    private final String className;

    public MiniCommand(String className) {
        this.className = className;
    }

    @Override
    public int execute(CommandContext ctx) {
        var allClasses = ctx.getAllClasses();
        ClassInfo ci = allClasses.stream()
                .filter(c -> c.name().equals(className) || c.qualifiedName().equals(className))
                .findFirst().orElse(null);

        if (ci == null) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", "Class not found: " + className);
            ctx.formatter().printResult(error);
            return 0;
        }

        CallGraph graph = ctx.callGraph();

        // Rank methods by caller count
        List<String> keyMethods = ci.methods().stream()
                .sorted(Comparator
                        .comparingInt((MethodInfo m) -> -countCallers(m, ci.name(), graph))
                        .thenComparingInt(m -> -(m.endLine() - m.startLine()))
                        .thenComparing(MethodInfo::name))
                .limit(MAX_METHODS)
                .map(MethodInfo::signature)
                .toList();

        // Key fields: exclude primitives, limit to MAX_FIELDS
        List<String> keyFields = ci.fields().stream()
                .filter(f -> !PRIMITIVE_TYPES.contains(f.type()))
                .limit(MAX_FIELDS)
                .map(f -> f.type() + " " + f.name())
                .toList();

        // Deps: unique field types (non-primitive)
        List<String> deps = ci.fields().stream()
                .map(FieldInfo::type)
                .filter(t -> !PRIMITIVE_TYPES.contains(t))
                .filter(t -> !t.equals("String"))
                .distinct()
                .limit(MAX_DEPS)
                .toList();

        // Count distinct classes that call any method
        long calledBy = ci.methods().stream()
                .flatMap(m -> graph.findMethodsByName(m.name()).stream())
                .flatMap(ref -> graph.getCallersOf(ref).stream())
                .map(call -> call.caller().className())
                .filter(name -> !name.equals(ci.name()))
                .distinct()
                .count();

        String kind = ci.isInterface() ? "interface" : ci.isAbstract() ? "abstract class" : "class";

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", ci.qualifiedName());
        result.put("pkg", abbreviatePackage(ci.packageName()));
        result.put("kind", kind);
        result.put("lines", ci.endLine() - ci.startLine());
        result.put("methods", ci.methods().size());
        result.put("keyMethods", keyMethods);
        result.put("keyFields", keyFields);
        result.put("deps", deps);
        result.put("calledBy", calledBy);

        ctx.formatter().printResult(result);
        return 1;
    }

    private int countCallers(MethodInfo m, String ownClass, CallGraph graph) {
        return (int) graph.findMethodsByName(m.name()).stream()
                .filter(ref -> ref.className().equals(ownClass))
                .flatMap(ref -> graph.getCallersOf(ref).stream())
                .map(call -> call.caller().className())
                .filter(name -> !name.equals(ownClass))
                .distinct()
                .count();
    }

    /**
     * Abbreviates package: org.springframework.boot.context → o.s.b.context
     * Keeps last segment full, abbreviates others to first char.
     */
    static String abbreviatePackage(String pkg) {
        if (pkg == null || pkg.isEmpty()) return "";
        String[] parts = pkg.split("\\.");
        if (parts.length <= 2) return pkg;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length - 1; i++) {
            sb.append(parts[i].charAt(0)).append('.');
        }
        sb.append(parts[parts.length - 1]);
        return sb.toString();
    }
}
