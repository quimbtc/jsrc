package com.jsrc.app.command;

import java.nio.file.Path;
import java.util.List;

import com.jsrc.app.parser.model.MethodInfo;
import com.jsrc.app.util.MethodResolver;

public class MethodSearchCommand implements Command {
    private final String methodInput;

    public MethodSearchCommand(String methodInput) {
        this.methodInput = methodInput;
    }

    @Override
    public int execute(CommandContext ctx) {
        var ref = MethodResolver.parse(methodInput);
        String methodName = ref.methodName();

        // Fast path: use index when available and no param type filtering needed
        if (ctx.indexed() != null && !ref.hasParamTypes()) {
            List<MethodInfo> methods = ctx.indexed().findMethodsByName(methodName);
            if (ref.hasClassName()) {
                methods = methods.stream()
                        .filter(m -> m.className().equals(ref.className()))
                        .toList();
            }
            if (!methods.isEmpty()) {
                // Re-parse only matching files for full method details
                int totalFound = 0;
                for (MethodInfo indexed : methods) {
                    String filePath = ctx.indexed().findFileForClass(indexed.className());
                    if (filePath == null) continue;
                    Path file = findMatchingFile(ctx.javaFiles(), filePath);
                    if (file == null) continue;
                    List<MethodInfo> detailed = ctx.parser().findMethods(file, methodName);
                    if (ref.hasClassName()) {
                        detailed = detailed.stream()
                                .filter(m -> m.className().equals(ref.className()))
                                .toList();
                    }
                    if (!detailed.isEmpty()) {
                        totalFound += detailed.size();
                        ctx.formatter().printMethods(detailed, file, methodName);
                    }
                }
                return totalFound;
            }
        }

        // Fallback: scan all files
        int totalFound = 0;
        for (Path file : ctx.javaFiles()) {
            List<MethodInfo> methods;
            if (ref.hasParamTypes()) {
                methods = ctx.parser().findMethods(file, methodName, ref.paramTypes());
            } else {
                methods = ctx.parser().findMethods(file, methodName);
            }
            if (ref.hasClassName()) {
                methods = methods.stream()
                        .filter(m -> m.className().equals(ref.className()))
                        .toList();
            }
            if (!methods.isEmpty()) {
                totalFound += methods.size();
                ctx.formatter().printMethods(methods, file, methodName);
            }
        }
        return totalFound;
    }

    private static Path findMatchingFile(List<Path> files, String indexPath) {
        for (Path f : files) {
            if (f.toString().endsWith(indexPath) || indexPath.endsWith(f.toString())) {
                return f;
            }
        }
        return null;
    }
}
