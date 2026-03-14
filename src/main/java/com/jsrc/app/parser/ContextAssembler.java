package com.jsrc.app.parser;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.jsrc.app.architecture.LayerResolver;
import com.jsrc.app.config.ArchitectureConfig;
import com.jsrc.app.parser.model.ClassInfo;
import com.jsrc.app.parser.model.CodeSmell;
import com.jsrc.app.parser.model.MethodInfo;

/**
 * Assembles a complete context package about a class for agent consumption.
 * One command, everything the agent needs for reverse engineering.
 */
public class ContextAssembler {

    private final CodeParser parser;
    private final DependencyAnalyzer dependencyAnalyzer;

    public ContextAssembler(CodeParser parser) {
        this(parser, new DependencyAnalyzer());
    }

    public ContextAssembler(CodeParser parser, DependencyAnalyzer dependencyAnalyzer) {
        this.parser = parser;
        this.dependencyAnalyzer = dependencyAnalyzer;
    }

    /**
     * Assembles full context for a class as a structured map.
     *
     * @param files        Java source files
     * @param className    class to analyze
     * @param allClasses   pre-parsed classes (from index or on-the-fly)
     * @param archConfig   architecture config (nullable)
     * @return context map, or null if class not found
     */
    public Map<String, Object> assemble(List<Path> files, String className,
                                         List<ClassInfo> allClasses,
                                         ArchitectureConfig archConfig) {
        // Find the target class
        ClassInfo target = allClasses.stream()
                .filter(ci -> ci.name().equals(className) || ci.qualifiedName().equals(className))
                .findFirst().orElse(null);
        if (target == null) return null;

        // Find the file
        Path targetFile = findFile(files, className);

        Map<String, Object> ctx = new LinkedHashMap<>();

        // Summary
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("name", target.name());
        summary.put("packageName", target.packageName());
        summary.put("qualifiedName", target.qualifiedName());
        summary.put("isInterface", target.isInterface());
        summary.put("isAbstract", target.isAbstract());
        summary.put("startLine", target.startLine());
        summary.put("endLine", target.endLine());
        if (!target.superClass().isEmpty()) summary.put("superClass", target.superClass());
        if (!target.interfaces().isEmpty()) summary.put("interfaces", target.interfaces());
        if (!target.annotations().isEmpty()) {
            summary.put("annotations", target.annotations().stream()
                    .map(a -> a.toString()).toList());
        }
        ctx.put("class", summary);

        // Methods with signatures
        List<Map<String, Object>> methods = target.methods().stream()
                .map(m -> {
                    Map<String, Object> mm = new LinkedHashMap<>();
                    mm.put("name", m.name());
                    mm.put("signature", m.signature());
                    mm.put("returnType", m.returnType());
                    mm.put("startLine", m.startLine());
                    mm.put("endLine", m.endLine());
                    if (!m.annotations().isEmpty()) {
                        mm.put("annotations", m.annotations().stream().map(a -> a.toString()).toList());
                    }
                    if (!m.thrownExceptions().isEmpty()) mm.put("throws", m.thrownExceptions());
                    return mm;
                }).toList();
        ctx.put("methods", methods);

        // Dependencies
        var deps = dependencyAnalyzer.analyze(files, className);
        if (deps != null) {
            Map<String, Object> depsMap = new LinkedHashMap<>();
            depsMap.put("imports", deps.imports());
            depsMap.put("fields", deps.fieldDependencies().stream()
                    .map(f -> f.type() + " " + f.name()).toList());
            depsMap.put("constructorParams", deps.constructorDependencies().stream()
                    .map(f -> f.type() + " " + f.name()).toList());
            ctx.put("dependencies", depsMap);
        }

        // Hierarchy
        Map<String, Object> hierarchy = new LinkedHashMap<>();
        if (!target.superClass().isEmpty()) hierarchy.put("superClass", target.superClass());
        if (!target.interfaces().isEmpty()) hierarchy.put("interfaces", target.interfaces());

        List<String> subClasses = allClasses.stream()
                .filter(ci -> ci.superClass().equals(target.name())
                        || ci.superClass().equals(target.qualifiedName()))
                .map(ClassInfo::qualifiedName).toList();
        if (!subClasses.isEmpty()) hierarchy.put("subClasses", subClasses);

        if (target.isInterface()) {
            List<String> implementors = allClasses.stream()
                    .filter(ci -> ci.interfaces().contains(target.name())
                            || ci.interfaces().contains(target.qualifiedName()))
                    .map(ClassInfo::qualifiedName).toList();
            if (!implementors.isEmpty()) hierarchy.put("implementors", implementors);
        }
        if (!hierarchy.isEmpty()) ctx.put("hierarchy", hierarchy);

        // Layer
        if (archConfig != null && !archConfig.layers().isEmpty()) {
            var resolver = new LayerResolver(archConfig.layers());
            String layer = resolver.resolve(target);
            if (layer != null) ctx.put("layer", layer);
        }

        // Callers/callees per method
        CallGraphBuilder graphBuilder = new CallGraphBuilder();
        graphBuilder.build(files);

        List<Map<String, Object>> methodCalls = new ArrayList<>();
        for (MethodInfo m : target.methods()) {
            var refs = graphBuilder.findMethodsByName(m.name());
            Map<String, Object> mc = new LinkedHashMap<>();
            mc.put("method", m.name());

            List<String> callersList = new ArrayList<>();
            for (var ref : refs) {
                for (var call : graphBuilder.getCallersOf(ref)) {
                    callersList.add(call.caller().displayName());
                }
            }
            if (!callersList.isEmpty()) mc.put("callers", callersList);

            List<String> calleesList = new ArrayList<>();
            for (var ref : refs) {
                for (var call : graphBuilder.getCalleesOf(ref)) {
                    calleesList.add(call.callee().displayName());
                }
            }
            if (!calleesList.isEmpty()) mc.put("callees", calleesList);

            if (!callersList.isEmpty() || !calleesList.isEmpty()) {
                methodCalls.add(mc);
            }
        }
        if (!methodCalls.isEmpty()) ctx.put("callGraph", methodCalls);

        // Code smells
        if (targetFile != null) {
            List<CodeSmell> smells = parser.detectSmells(targetFile);
            if (!smells.isEmpty()) {
                ctx.put("smells", smells.stream().map(s -> {
                    Map<String, Object> sm = new LinkedHashMap<>();
                    sm.put("ruleId", s.ruleId());
                    sm.put("severity", s.severity().name());
                    sm.put("message", s.message());
                    sm.put("line", s.line());
                    return sm;
                }).toList());
            }
        }

        // Source
        if (targetFile != null) {
            var reader = new SourceReader(parser);
            var readResult = reader.readClass(files, className);
            if (readResult != null) {
                ctx.put("source", readResult.content());
            }
        }

        if (targetFile != null) ctx.put("file", targetFile.toString());

        return ctx;
    }

    private Path findFile(List<Path> files, String className) {
        for (Path file : files) {
            if (file.getFileName().toString().equals(className + ".java")) {
                return file;
            }
        }
        return null;
    }
}
