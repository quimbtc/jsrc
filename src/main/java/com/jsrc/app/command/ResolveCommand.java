package com.jsrc.app.command;

import java.util.LinkedHashMap;
import java.util.Map;

import com.jsrc.app.parser.model.ClassInfo;
import com.jsrc.app.parser.model.FieldInfo;

/**
 * Resolves the type of a receiver variable using field/constructor info from the index.
 * E.g. in OrderController, "service.process()" → service is type OrderService.
 */
public class ResolveCommand implements Command {

    private final String expression;

    public ResolveCommand(String expression) {
        this.expression = expression;
    }

    @Override
    public int execute(CommandContext ctx) {
        // Parse: variable.method() or Class.variable.method()
        String[] parts = expression.split("\\.");
        if (parts.length < 2) {
            ctx.formatter().printResult(Map.of("error", "Expected format: variable.method or Class.variable.method"));
            return 0;
        }

        String contextClass = null;
        String variable;
        String method;

        if (parts.length >= 3 && Character.isUpperCase(parts[0].charAt(0))) {
            contextClass = parts[0];
            variable = parts[1];
            method = parts[2].replaceAll("\\(.*", "");
        } else {
            variable = parts[0];
            method = parts[1].replaceAll("\\(.*", "");
        }

        // Find the context class
        var allClasses = ctx.getAllClasses();
        ClassInfo context = null;
        if (contextClass != null) {
            String finalCtx = contextClass;
            context = allClasses.stream()
                    .filter(c -> c.name().equals(finalCtx))
                    .findFirst().orElse(null);
        }

        if (context == null && contextClass != null) {
            ctx.formatter().printResult(Map.of("error", "Context class not found: " + contextClass));
            return 0;
        }

        // Resolve variable type from fields
        String resolvedType = null;
        String resolvedVia = null;

        if (context != null) {
            for (FieldInfo f : context.fields()) {
                if (f.name().equals(variable)) {
                    resolvedType = f.type();
                    resolvedVia = "field";
                    break;
                }
            }
        }

        // If "this" → context class itself
        if ("this".equals(variable) && context != null) {
            resolvedType = context.name();
            resolvedVia = "this";
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("expression", expression);
        if (contextClass != null) result.put("context", contextClass);
        result.put("variable", variable);
        result.put("method", method);

        if (resolvedType != null) {
            result.put("resolvedType", resolvedType);
            result.put("resolvedVia", resolvedVia);

            // Find the method in the resolved type
            if (ctx.indexed() != null) {
                for (var entry : ctx.indexed().getEntries()) {
                    for (var ic : entry.classes()) {
                        if (ic.name().equals(resolvedType)) {
                            for (var im : ic.methods()) {
                                if (im.name().equals(method)) {
                                    result.put("signature", im.signature());
                                    result.put("returnType", im.returnType());
                                    break;
                                }
                            }
                        }
                    }
                }
            }
        } else {
            result.put("resolvedType", "unknown");
            result.put("error", "Could not resolve type of '" + variable + "'");
        }

        ctx.formatter().printResult(result);
        return resolvedType != null ? 1 : 0;
    }
}
