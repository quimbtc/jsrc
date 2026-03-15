package com.jsrc.app.command;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.jsrc.app.output.JsonWriter;
import com.jsrc.app.parser.model.ClassInfo;
import com.jsrc.app.parser.model.MethodInfo;

/**
 * Finds classes with similar structure (same methods, same interfaces).
 * Detects structural duplication and repeated patterns.
 */
public class SimilarCommand implements Command {
    private final String className;

    public SimilarCommand(String className) {
        this.className = className;
    }

    @Override
    public int execute(CommandContext ctx) {
        var allClasses = ctx.getAllClasses();
        ClassInfo target = SummaryCommand.resolveOrExit(allClasses, className);
        if (target == null) return 0;

        Set<String> targetMethods = new HashSet<>();
        for (MethodInfo m : target.methods()) {
            targetMethods.add(m.name());
        }
        Set<String> targetInterfaces = new HashSet<>(target.interfaces());

        List<Map<String, Object>> similar = new ArrayList<>();
        for (ClassInfo ci : allClasses) {
            if (ci.name().equals(target.name()) && ci.packageName().equals(target.packageName())) continue;

            // Calculate similarity score
            Set<String> ciMethods = new HashSet<>();
            for (MethodInfo m : ci.methods()) ciMethods.add(m.name());

            // Method overlap
            Set<String> commonMethods = new HashSet<>(targetMethods);
            commonMethods.retainAll(ciMethods);
            Set<String> allMethods = new HashSet<>(targetMethods);
            allMethods.addAll(ciMethods);

            double methodSimilarity = allMethods.isEmpty() ? 0
                    : (double) commonMethods.size() / allMethods.size();

            // Interface overlap
            Set<String> commonInterfaces = new HashSet<>(targetInterfaces);
            commonInterfaces.retainAll(ci.interfaces());
            double ifaceSimilarity = targetInterfaces.isEmpty() && ci.interfaces().isEmpty() ? 0
                    : targetInterfaces.isEmpty() || ci.interfaces().isEmpty() ? 0
                    : (double) commonInterfaces.size() / Math.max(targetInterfaces.size(), ci.interfaces().size());

            double score = (methodSimilarity * 0.7) + (ifaceSimilarity * 0.3);

            if (score >= 0.3) { // 30% similarity threshold
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("className", ci.qualifiedName());
                entry.put("similarity", Math.round(score * 100));
                entry.put("commonMethods", List.copyOf(commonMethods));
                if (!commonInterfaces.isEmpty()) {
                    entry.put("commonInterfaces", List.copyOf(commonInterfaces));
                }
                similar.add(entry);
            }
        }

        // Sort by similarity descending
        similar.sort((a, b) -> Long.compare((long) b.get("similarity"), (long) a.get("similarity")));

        System.out.println(JsonWriter.toJson(similar));
        return similar.size();
    }
}
