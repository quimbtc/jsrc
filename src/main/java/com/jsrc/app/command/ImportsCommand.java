package com.jsrc.app.command;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.jsrc.app.output.JsonWriter;
import com.jsrc.app.parser.DependencyAnalyzer;
import com.jsrc.app.parser.model.ClassInfo;

/**
 * Finds all classes that import/depend on a given class.
 * Inverse of --deps. Critical for impact analysis.
 */
public class ImportsCommand implements Command {
    private final String className;

    public ImportsCommand(String className) {
        this.className = className;
    }

    @Override
    public int execute(CommandContext ctx) {
        var analyzer = new DependencyAnalyzer();
        var allClasses = ctx.getAllClasses();
        List<Map<String, Object>> dependents = new ArrayList<>();

        for (ClassInfo ci : allClasses) {
            if (ci.name().equals(className) || ci.qualifiedName().equals(className)) continue;

            var deps = analyzer.analyze(ctx.javaFiles(), ci.name());
            if (deps == null) continue;

            boolean depends = false;
            String relationship = "";

            // Check imports
            for (String imp : deps.imports()) {
                if (imp.endsWith("." + className) || imp.equals(className)) {
                    depends = true;
                    relationship = "import";
                    break;
                }
            }

            // Check field types
            if (!depends) {
                for (var field : deps.fieldDependencies()) {
                    if (field.type().equals(className)) {
                        depends = true;
                        relationship = "field (" + field.name() + ")";
                        break;
                    }
                }
            }

            // Check constructor params
            if (!depends) {
                for (var param : deps.constructorDependencies()) {
                    if (param.type().equals(className)) {
                        depends = true;
                        relationship = "constructor (" + param.name() + ")";
                        break;
                    }
                }
            }

            if (depends) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("className", ci.qualifiedName());
                entry.put("relationship", relationship);
                dependents.add(entry);
            }
        }

        System.out.println(JsonWriter.toJson(dependents));
        return dependents.size();
    }
}
