package com.jsrc.app.command;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.jsrc.app.output.JsonWriter;
import com.jsrc.app.parser.model.ClassInfo;

/**
 * Maps package structure with inter-package dependencies.
 */
public class PackagesCommand implements Command {
    @Override
    public int execute(CommandContext ctx) {
        var allClasses = ctx.getAllClasses();

        // Group classes by package
        Map<String, List<ClassInfo>> byPackage = new TreeMap<>();
        for (ClassInfo ci : allClasses) {
            String pkg = ci.packageName().isEmpty() ? "(default)" : ci.packageName();
            byPackage.computeIfAbsent(pkg, k -> new ArrayList<>()).add(ci);
        }

        // Analyze dependencies between packages
        List<Map<String, Object>> result = new ArrayList<>();
        for (var entry : byPackage.entrySet()) {
            String pkg = entry.getKey();
            List<ClassInfo> classes = entry.getValue();
            Set<String> dependsOn = new LinkedHashSet<>();

            for (ClassInfo ci : classes) {
                var deps = ctx.indexed() != null
                        ? ctx.indexed().getDependencies(ci.name())
                        : ctx.dependencyAnalyzer().analyze(ctx.javaFiles(), ci.name());
                if (deps.isEmpty()) continue;
                for (String imp : deps.get().imports()) {
                    int lastDot = imp.lastIndexOf('.');
                    if (lastDot > 0) {
                        String importPkg = imp.substring(0, lastDot);
                        if (!importPkg.equals(pkg) && byPackage.containsKey(importPkg)) {
                            dependsOn.add(importPkg);
                        }
                    }
                }
            }

            Map<String, Object> pkgInfo = new LinkedHashMap<>();
            pkgInfo.put("name", pkg);
            pkgInfo.put("classes", classes.size());
            pkgInfo.put("methods", classes.stream().mapToInt(c -> c.methods().size()).sum());
            if (!dependsOn.isEmpty()) pkgInfo.put("dependsOn", List.copyOf(dependsOn));
            result.add(pkgInfo);
        }

        if (!ctx.fullOutput() && result.size() > 30) {
            // Compact: top 30 packages by class count + summary
            // Strip import details — only name + class count
            var sorted = result.stream()
                    .sorted((a, b) -> Integer.compare(
                            ((Number) b.get("classes")).intValue(),
                            ((Number) a.get("classes")).intValue()))
                    .limit(30)
                    .map(p -> {
                        var slim = new LinkedHashMap<String, Object>();
                        slim.put("name", p.get("name"));
                        slim.put("classes", p.get("classes"));
                        slim.put("methods", p.get("methods"));
                        return slim;
                    })
                    .toList();
            var compact = new LinkedHashMap<String, Object>();
            compact.put("totalPackages", result.size());
            compact.put("totalClasses", result.stream()
                    .mapToInt(p -> ((Number) p.get("classes")).intValue()).sum());
            compact.put("packages", sorted);
            compact.put("truncated", true);
            compact.put("hint", "Use --full to see all " + result.size() + " packages");
            ctx.formatter().printResult(compact);
        } else {
            ctx.formatter().printResult(result);
        }
        return result.size();
    }
}
