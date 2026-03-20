package com.jsrc.app.command;

import java.nio.file.Path;

public class ClassesCommand implements Command {
    @Override
    public int execute(CommandContext ctx) {
        var allClasses = ctx.getAllClasses();
        // Compact mode (default): show count + first 50 classes
        if (!ctx.fullOutput() && allClasses.size() > 50) {
            var compact = new java.util.LinkedHashMap<String, Object>();
            compact.put("total", allClasses.size());
            // Rank by caller count (most referenced = most important)
            var graph = ctx.callGraph();
            compact.put("classes", allClasses.stream()
                    .sorted((a, b) -> {
                        long callersA = graph.findMethodsByName(a.name()).stream()
                                .mapToLong(r -> graph.getCallersOf(r).size()).sum();
                        long callersB = graph.findMethodsByName(b.name()).stream()
                                .mapToLong(r -> graph.getCallersOf(r).size()).sum();
                        return Long.compare(callersB, callersA);
                    })
                    .limit(50)
                    .map(ci -> ci.qualifiedName().isEmpty() ? ci.name() : ci.qualifiedName())
                    .toList());
            compact.put("truncated", true);
            compact.put("hint", "Use --full to see all classes");
            ctx.formatter().printResult(compact);
        } else {
            ctx.formatter().printClasses(allClasses, Path.of(ctx.rootPath()));
        }
        return allClasses.size();
    }
}
