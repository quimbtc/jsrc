package com.jsrc.app.command;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.jsrc.app.parser.model.ClassInfo;

/**
 * Analyzes breaking change impact via inheritance, interfaces, and overrides.
 * Complements --impact (which only shows callers) with structural dependencies.
 */
public class BreakingChangesCommand implements Command {

    private final String className;

    public BreakingChangesCommand(String className) {
        this.className = className;
    }

    @Override
    public int execute(CommandContext ctx) {
        var allClasses = ctx.getAllClasses();
        ClassInfo target = allClasses.stream()
                .filter(ci -> ci.name().equals(className) || ci.qualifiedName().equals(className))
                .findFirst().orElse(null);

        if (target == null) {
            ctx.formatter().printResult(Map.of("error", "Class not found: " + className));
            return 0;
        }

        // Subclasses
        List<String> subClasses = allClasses.stream()
                .filter(ci -> ci.superClass().equals(target.name()) || ci.superClass().equals(target.qualifiedName()))
                .map(ci -> ctx.qualify(ci.name()))
                .toList();

        // Implementors (if target is interface) — strip generics for matching
        List<String> implementors = List.of();
        if (target.isInterface()) {
            implementors = allClasses.stream()
                    .filter(ci -> ci.interfaces().stream().anyMatch(iface -> {
                        String stripped = iface.contains("<") ? iface.substring(0, iface.indexOf('<')) : iface;
                        return stripped.equals(target.name()) || stripped.equals(target.qualifiedName());
                    }))
                    .map(ci -> ctx.qualify(ci.name()))
                    .toList();
        }

        // Overridden methods
        List<Map<String, Object>> overriddenMethods = new ArrayList<>();
        for (var method : target.methods()) {
            List<String> overriddenBy = new ArrayList<>();
            for (var sub : allClasses) {
                boolean isChild = sub.superClass().equals(target.name()) || sub.superClass().equals(target.qualifiedName());
                boolean isImpl = sub.interfaces().stream().anyMatch(iface -> {
                    String stripped = iface.contains("<") ? iface.substring(0, iface.indexOf('<')) : iface;
                    return stripped.equals(target.name()) || stripped.equals(target.qualifiedName());
                });
                if (!isChild && !isImpl)
                    continue;
                if (sub.methods().stream().anyMatch(m -> m.name().equals(method.name()))) {
                    overriddenBy.add(ctx.qualify(sub.name()) + "." + method.name());
                }
            }
            if (!overriddenBy.isEmpty()) {
                var entry = new LinkedHashMap<String, Object>();
                entry.put("method", method.name());
                entry.put("overriddenBy", overriddenBy);
                overriddenMethods.add(entry);
            }
        }

        // Breaking conditions
        List<String> breakingIf = new ArrayList<>();
        if (!subClasses.isEmpty()) breakingIf.add("method signature changed (breaks subclasses)");
        if (!implementors.isEmpty()) breakingIf.add("method added/removed (breaks implementors)");
        if (target.isInterface()) breakingIf.add("abstract method added (all implementors must update)");
        if (target.isAbstract()) breakingIf.add("abstract method added (all subclasses must implement)");
        if (!overriddenMethods.isEmpty()) breakingIf.add("overridden method signature changed");

        // Risk
        int total = subClasses.size() + implementors.size();
        String risk = total == 0 ? "none" : total <= 3 ? "low" : total <= 10 ? "medium" : "high";

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("target", ctx.qualify(target.name()));
        result.put("isInterface", target.isInterface());
        result.put("isAbstract", target.isAbstract());
        result.put("subClasses", subClasses);
        result.put("implementors", implementors);
        result.put("overriddenMethods", overriddenMethods);
        result.put("breakingIf", breakingIf);
        result.put("riskLevel", risk);

        ctx.formatter().printResult(result);
        return total;
    }
}
