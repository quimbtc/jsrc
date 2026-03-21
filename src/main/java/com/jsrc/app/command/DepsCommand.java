package com.jsrc.app.command;


public class DepsCommand implements Command {
    private final String className;

    public DepsCommand(String className) {
        this.className = className;
    }

    @Override
    public int execute(CommandContext ctx) {
        com.jsrc.app.model.DependencyResult deps = null;

        // Fast path: use index if available
        if (ctx.indexed() != null) {
            var indexed = ctx.indexed().getDependencies(className);
            if (indexed.isPresent()) deps = indexed.get();
        }
        // Fallback: parse on-the-fly
        if (deps == null) {
            var result = ctx.dependencyAnalyzer().analyze(ctx.javaFiles(), className);
            if (result.isPresent()) deps = result.get();
        }

        if (deps == null) {
            System.err.printf("Class '%s' not found.%n", className);
            return 0;
        }

        if (!ctx.fullOutput()) {
            // Compact: filter out java.*/javax.* imports, show only types
            var compact = new java.util.LinkedHashMap<String, Object>();
            compact.put("className", deps.className());
            compact.put("imports", deps.imports().stream()
                    .filter(i -> !i.startsWith("java.") && !i.startsWith("javax.")
                            && !i.startsWith("jakarta."))
                    .toList());
            compact.put("fieldTypes", deps.fieldDependencies().stream()
                    .map(com.jsrc.app.model.DependencyResult.FieldDep::type)
                    .distinct().toList());
            compact.put("constructorParamTypes", deps.constructorDependencies().stream()
                    .map(com.jsrc.app.model.DependencyResult.FieldDep::type)
                    .distinct().toList());
            ctx.formatter().printResult(compact);
        } else {
            ctx.formatter().printDependencies(deps);
        }
        return 1;
    }
}
