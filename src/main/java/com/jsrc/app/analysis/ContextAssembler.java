package com.jsrc.app.analysis;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.jsrc.app.architecture.LayerResolver;
import com.jsrc.app.config.ArchitectureConfig;
import com.jsrc.app.model.ContextResult;
import com.jsrc.app.model.ContextResult.HierarchyInfo;
import com.jsrc.app.model.ContextResult.MethodCallInfo;
import com.jsrc.app.model.DependencyResult;
import com.jsrc.app.parser.CodeParser;
import com.jsrc.app.parser.SourceReader;
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
     * Assembles full context for a class.
     *
     * @param files        Java source files
     * @param className    class to analyze
     * @param allClasses   pre-parsed classes (from index or on-the-fly)
     * @param archConfig   architecture config (nullable)
     * @param callGraph    pre-built call graph (nullable — builds its own if null)
     * @return typed context result, or null if class not found
     */
    public ContextResult assemble(List<Path> files, String className,
                                   List<ClassInfo> allClasses,
                                   ArchitectureConfig archConfig,
                                   CallGraph callGraph) {
        ClassInfo target = allClasses.stream()
                .filter(ci -> ci.name().equals(className) || ci.qualifiedName().equals(className))
                .findFirst().orElse(null);
        if (target == null) return null;

        Path targetFile = findFile(files, className);

        DependencyResult deps = dependencyAnalyzer.analyze(files, className);

        HierarchyInfo hierarchy = buildHierarchy(target, allClasses);

        String layer = resolveLayer(target, archConfig);

        CallGraph graph = callGraph != null ? callGraph : buildCallGraph(files);
        List<MethodCallInfo> methodCalls = buildMethodCalls(target, graph);

        List<CodeSmell> smells = targetFile != null
                ? parser.detectSmells(targetFile) : List.of();

        String source = readSource(files, className, targetFile);

        String file = targetFile != null ? targetFile.toString() : null;

        return new ContextResult(target, deps, hierarchy, layer, methodCalls, smells, source, file);
    }

    /**
     * Backward-compatible: assembles without external call graph.
     */
    public ContextResult assemble(List<Path> files, String className,
                                   List<ClassInfo> allClasses,
                                   ArchitectureConfig archConfig) {
        return assemble(files, className, allClasses, archConfig, null);
    }

    private HierarchyInfo buildHierarchy(ClassInfo target, List<ClassInfo> allClasses) {
        String superClass = target.superClass();
        List<String> interfaces = target.interfaces();

        List<String> subClasses = allClasses.stream()
                .filter(ci -> ci.superClass().equals(target.name())
                        || ci.superClass().equals(target.qualifiedName()))
                .map(ClassInfo::qualifiedName).toList();

        List<String> implementors = List.of();
        if (target.isInterface()) {
            implementors = allClasses.stream()
                    .filter(ci -> ci.interfaces().contains(target.name())
                            || ci.interfaces().contains(target.qualifiedName()))
                    .map(ClassInfo::qualifiedName).toList();
        }

        if (superClass.isEmpty() && interfaces.isEmpty()
                && subClasses.isEmpty() && implementors.isEmpty()) {
            return null;
        }
        return new HierarchyInfo(superClass, interfaces, subClasses, implementors);
    }

    private String resolveLayer(ClassInfo target, ArchitectureConfig archConfig) {
        if (archConfig == null || archConfig.layers().isEmpty()) return null;
        var resolver = new LayerResolver(archConfig.layers());
        return resolver.resolve(target);
    }

    private List<MethodCallInfo> buildMethodCalls(ClassInfo target, CallGraph graph) {
        List<MethodCallInfo> result = new ArrayList<>();
        for (MethodInfo m : target.methods()) {
            var refs = graph.findMethodsByName(m.name());

            List<String> callers = new ArrayList<>();
            for (var ref : refs) {
                for (var call : graph.getCallersOf(ref)) {
                    callers.add(call.caller().displayName());
                }
            }

            List<String> callees = new ArrayList<>();
            for (var ref : refs) {
                for (var call : graph.getCalleesOf(ref)) {
                    callees.add(call.callee().displayName());
                }
            }

            if (!callers.isEmpty() || !callees.isEmpty()) {
                result.add(new MethodCallInfo(m.name(), callers, callees));
            }
        }
        return result;
    }

    private CallGraph buildCallGraph(List<Path> files) {
        var builder = new CallGraphBuilder();
        builder.build(files);
        return builder.toCallGraph();
    }

    private String readSource(List<Path> files, String className, Path targetFile) {
        if (targetFile == null) return null;
        var reader = new SourceReader(parser);
        var readResult = reader.readClass(files, className);
        return readResult != null ? readResult.content() : null;
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
