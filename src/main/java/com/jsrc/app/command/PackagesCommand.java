package com.jsrc.app.command;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.jsrc.app.output.JsonWriter;
import com.jsrc.app.parser.DependencyAnalyzer;
import com.jsrc.app.parser.model.ClassInfo;

/**
 * Maps package structure with inter-package dependencies.
 */
public class PackagesCommand implements Command {
    @Override
    public int execute(CommandContext ctx) {
        var allClasses = ctx.getAllClasses();
        var analyzer = new DependencyAnalyzer();

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
                var deps = analyzer.analyze(ctx.javaFiles(), ci.name());
                if (deps == null) continue;
                for (String imp : deps.imports()) {
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

        System.out.println(JsonWriter.toJson(result));
        return result.size();
    }
}
