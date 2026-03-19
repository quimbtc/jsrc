package com.jsrc.app.command;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.jsrc.app.analysis.CallGraph;
import com.jsrc.app.parser.model.ClassInfo;
import com.jsrc.app.parser.model.FieldInfo;

/**
 * Extracts a code template from a real class in the codebase.
 * The template preserves the project's actual patterns (annotations,
 * injection style, naming) with specific names replaced by ${Name} placeholders.
 * <p>
 * Supported patterns: service, repository, controller, config, record, handler
 */
public class SnippetCommand implements Command {

    private static final Map<String, String> PATTERN_SUFFIXES = Map.of(
            "service", "Service",
            "repository", "Repository",
            "controller", "Controller",
            "config", "Configuration",
            "handler", "Handler",
            "factory", "Factory",
            "record", "",  // special handling
            "adapter", "Adapter"
    );

    private final String pattern;

    public SnippetCommand(String pattern) {
        this.pattern = pattern.toLowerCase();
    }

    @Override
    public int execute(CommandContext ctx) {
        var allClasses = ctx.getAllClasses();

        // Find matching classes
        List<ClassInfo> candidates;
        if ("record".equals(pattern)) {
            // Records don't have a suffix — find by checking if it looks like a record
            // (no superclass, few methods, has fields but no setter)
            candidates = allClasses.stream()
                    .filter(ci -> !ci.fields().isEmpty()
                            && ci.methods().stream().noneMatch(m -> m.name().startsWith("set")))
                    .toList();
        } else {
            String suffix = PATTERN_SUFFIXES.getOrDefault(pattern, pattern.substring(0, 1).toUpperCase() + pattern.substring(1));
            candidates = allClasses.stream()
                    .filter(ci -> ci.name().endsWith(suffix))
                    .toList();
        }

        if (candidates.isEmpty()) {
            ctx.formatter().printResult(Map.of(
                    "error", "No classes matching pattern '" + pattern + "' found",
                    "availablePatterns", PATTERN_SUFFIXES.keySet().stream().sorted().toList()));
            return 0;
        }

        // Select the most representative: prefer mid-size, most callers
        CallGraph graph = ctx.callGraph();
        ClassInfo selected = candidates.stream()
                .sorted(Comparator
                        .comparingInt((ClassInfo ci) -> -countExternalCallers(ci, graph))
                        .thenComparingInt(ci -> -ci.methods().size()))
                .findFirst()
                .orElse(candidates.getFirst());

        // Extract template
        String baseName = extractBaseName(selected.name(), PATTERN_SUFFIXES.getOrDefault(pattern, ""));

        // Build template string
        StringBuilder template = new StringBuilder();

        // Annotations
        if (ctx.indexed() != null) {
            for (var entry : ctx.indexed().getEntries()) {
                for (var ic : entry.classes()) {
                    if (ic.name().equals(selected.name())) {
                        for (String ann : ic.annotations()) {
                            template.append("@").append(ann).append("\n");
                        }
                    }
                }
            }
        }

        // Class declaration
        String suffix = PATTERN_SUFFIXES.getOrDefault(pattern, "");
        template.append("public class ${Name}").append(suffix).append(" {\n");

        // Fields (templatized)
        for (FieldInfo f : selected.fields()) {
            String type = f.type();
            String name = f.name();
            // Replace the base name if it appears in type
            type = type.replace(baseName, "${Dep}");
            template.append("    private final ").append(type).append(" ").append(name).append(";\n");
        }

        // Constructor if has fields
        if (!selected.fields().isEmpty()) {
            template.append("\n    public ${Name}").append(suffix).append("(");
            var params = selected.fields().stream()
                    .map(f -> f.type().replace(baseName, "${Dep}") + " " + f.name())
                    .toList();
            template.append(String.join(", ", params));
            template.append(") {\n");
            for (FieldInfo f : selected.fields()) {
                template.append("        this.").append(f.name()).append(" = ").append(f.name()).append(";\n");
            }
            template.append("    }\n");
        }

        // Method signatures (first 3)
        var methods = selected.methods().stream()
                .filter(m -> !m.name().equals(selected.name())) // skip constructors
                .limit(3)
                .toList();
        for (var m : methods) {
            template.append("\n    ").append(m.signature()).append(" { /* TODO */ }\n");
        }

        template.append("}\n");

        // Imports from index
        List<String> imports = List.of();
        if (ctx.indexed() != null) {
            for (var entry : ctx.indexed().getEntries()) {
                for (var ic : entry.classes()) {
                    if (ic.name().equals(selected.name())) {
                        imports = ic.imports().stream()
                                .filter(i -> !i.startsWith("java.lang"))
                                .toList();
                    }
                }
            }
        }

        // Infer conventions
        List<String> conventions = new ArrayList<>();
        if (selected.fields().stream().anyMatch(f -> f.type().contains("Logger")))
            conventions.add("SLF4J logger");
        if (!selected.fields().isEmpty() && selected.methods().stream()
                .anyMatch(m -> m.name().equals(selected.name())))
            conventions.add("Constructor injection");
        if (!selected.annotations().isEmpty())
            conventions.add("Annotated: " + selected.annotations().stream()
                    .map(a -> "@" + a.name()).reduce((a, b) -> a + ", " + b).orElse(""));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pattern", pattern);
        result.put("basedOn", selected.qualifiedName());
        result.put("template", template.toString());
        result.put("imports", imports);
        result.put("conventions", conventions);

        ctx.formatter().printResult(result);
        return 1;
    }

    private String extractBaseName(String className, String suffix) {
        if (!suffix.isEmpty() && className.endsWith(suffix)) {
            return className.substring(0, className.length() - suffix.length());
        }
        return className;
    }

    private int countExternalCallers(ClassInfo ci, CallGraph graph) {
        return (int) ci.methods().stream()
                .flatMap(m -> graph.findMethodsByName(m.name()).stream())
                .filter(ref -> ref.className().equals(ci.name()))
                .flatMap(ref -> graph.getCallersOf(ref).stream())
                .map(call -> call.caller().className())
                .filter(name -> !name.equals(ci.name()))
                .distinct()
                .count();
    }
}
