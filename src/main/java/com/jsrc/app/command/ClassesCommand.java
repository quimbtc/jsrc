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
            compact.put("classes", allClasses.stream()
                    .map(ci -> ci.qualifiedName().isEmpty() ? ci.name() : ci.qualifiedName())
                    .sorted()
                    .limit(50)
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
