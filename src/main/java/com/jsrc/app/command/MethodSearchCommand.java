package com.jsrc.app.command;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

        List<Map<String, Object>> allResults = new ArrayList<>();

        // Fast path: use index when available and no param type filtering needed
        if (ctx.indexed() != null && !ref.hasParamTypes()) {
            List<MethodInfo> methods = ctx.indexed().findMethodsByName(methodName);
            if (ref.hasClassName()) {
                methods = methods.stream()
                        .filter(m -> m.className().equals(ref.className()))
                        .toList();
            }
            if (!methods.isEmpty()) {
                for (MethodInfo indexed : methods) {
                    var filePathOpt = ctx.indexed().findFileForClass(indexed.className());
                    if (filePathOpt.isEmpty()) continue;
                    String filePath = filePathOpt.get();
                    Path file = findMatchingFile(ctx.javaFiles(), filePath);
                    if (file == null) continue;
                    List<MethodInfo> detailed = ctx.parser().findMethods(file, methodName);
                    if (ref.hasClassName()) {
                        detailed = detailed.stream()
                                .filter(m -> m.className().equals(ref.className()))
                                .toList();
                    }
                    for (MethodInfo m : detailed) {
                        allResults.add(methodToMap(m, file));
                    }
                }
                return outputResults(ctx, allResults, methodName);
            }
        }

        // Fallback: scan all files
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
            for (MethodInfo m : methods) {
                allResults.add(methodToMap(m, file));
            }
        }
        return outputResults(ctx, allResults, methodName);
    }

    private int outputResults(CommandContext ctx, List<Map<String, Object>> rawResults, String methodName) {
        // Deduplicate by class+name+line (index fast path can produce dupes)
        var seen = new java.util.LinkedHashSet<String>();
        List<Map<String, Object>> results = new ArrayList<>();
        for (var r : rawResults) {
            String key = r.get("class") + "." + r.get("name") + ":" + r.get("line");
            if (seen.add(key)) results.add(r);
        }
        if (!ctx.fullOutput() && results.size() > 30) {
            var compact = new LinkedHashMap<String, Object>();
            compact.put("method", methodName);
            compact.put("total", results.size());
            // Group by class
            var byClass = new LinkedHashMap<String, Integer>();
            for (var r : results) {
                String cls = (String) r.getOrDefault("className", "");
                if (!cls.isEmpty()) byClass.merge(cls, 1, Integer::sum);
            }
            compact.put("classesWithMethod", byClass.size());
            compact.put("topClasses", byClass.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(10)
                    .map(e -> Map.of("class", e.getKey(), "overloads", e.getValue()))
                    .toList());
            compact.put("matches", results.subList(0, 30));
            compact.put("truncated", true);
            compact.put("hint", "Use --full or Class.method to narrow results");
            ctx.formatter().printResult(compact);
        } else {
            ctx.formatter().printResult(results);
        }
        return results.size();
    }

    private Map<String, Object> methodToMap(MethodInfo m, Path file) {
        var map = new LinkedHashMap<String, Object>();
        map.put("name", m.name());
        map.put("class", m.className());
        map.put("signature", m.signature());
        map.put("file", file.toString());
        map.put("line", m.startLine());
        return map;
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
