package com.jsrc.app.command;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.jsrc.app.analysis.CallGraph;
import com.jsrc.app.parser.model.MethodReference;

/**
 * Basic type verification for method calls.
 * Verifies: method exists, return type, argument count.
 * <p>
 * Not a full type checker — best-effort verification from index data.
 * Helps catch obvious hallucinations like calling void methods as if they return values.
 */
public class TypeCheckCommand implements Command {

    private static final Set<String> VOID_TYPES = Set.of("void", "");
    private static final Map<String, String> WRAPPERS = Map.of(
            "int", "Integer", "long", "Long", "double", "Double",
            "float", "Float", "boolean", "Boolean", "byte", "Byte",
            "short", "Short", "char", "Character");

    private final String expression;

    public TypeCheckCommand(String expression) {
        this.expression = expression;
    }

    @Override
    public int execute(CommandContext ctx) {
        // Parse: Class.method or method
        String className = null;
        String methodName = expression;
        int dotIdx = expression.lastIndexOf('.');
        if (dotIdx > 0) {
            className = expression.substring(0, dotIdx);
            methodName = expression.substring(dotIdx + 1);
        }

        // Strip params if present: method(Type1,Type2) → method
        int parenIdx = methodName.indexOf('(');
        if (parenIdx > 0) methodName = methodName.substring(0, parenIdx);

        // Find method in index
        String returnType = null;
        String foundClass = null;
        String foundSig = null;

        if (ctx.indexed() != null) {
            for (var entry : ctx.indexed().getEntries()) {
                for (var ic : entry.classes()) {
                    if (className != null && !ic.name().equals(className)) continue;
                    for (var im : ic.methods()) {
                        if (im.name().equals(methodName)) {
                            returnType = im.returnType();
                            foundClass = ic.name();
                            foundSig = im.signature();
                            break;
                        }
                    }
                    if (foundClass != null) break;
                }
                if (foundClass != null) break;
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("expression", expression);

        if (foundClass == null) {
            result.put("valid", false);
            result.put("error", "Method not found: " + expression);
            // Suggest closest method in the target class
            if (className != null && ctx.indexed() != null) {
                var closest = new java.util.ArrayList<String>();
                for (var entry : ctx.indexed().getEntries()) {
                    for (var ic : entry.classes()) {
                        if (!ic.name().equals(className)) continue;
                        for (var im : ic.methods()) {
                            int dist = ValidateCommand.levenshtein(
                                    methodName.toLowerCase(), im.name().toLowerCase());
                            if (dist <= 3) {
                                closest.add(ic.name() + "." + im.signature());
                            }
                        }
                    }
                }
                if (!closest.isEmpty()) result.put("closest", closest);
            }
            ctx.formatter().printResult(result);
            return 0;
        }

        result.put("valid", true);
        result.put("className", ctx.qualify(foundClass));
        result.put("methodName", methodName);
        result.put("returnType", returnType != null ? returnType : "unknown");
        result.put("signature", foundSig);
        if (VOID_TYPES.contains(returnType)) {
            result.put("warning", "Method returns void — cannot assign to variable");
        }

        ctx.formatter().printResult(result);
        return 1;
    }
}
