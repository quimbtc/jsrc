package com.jsrc.app.command;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.jsrc.app.analysis.CallGraph;
import com.jsrc.app.analysis.CallGraphBuilder;
import com.jsrc.app.analysis.DependencyAnalyzer;
import com.jsrc.app.config.ProjectConfig;
import com.jsrc.app.index.IndexedCodebase;
import com.jsrc.app.output.OutputFormatter;
import com.jsrc.app.parser.CodeParser;
import com.jsrc.app.parser.SourceReader;
import com.jsrc.app.parser.model.ClassInfo;

/**
 * Shared context for all commands. Created once, passed to each command.
 * Provides lazy-loaded shared services (call graph, etc.) to avoid
 * redundant expensive operations across commands.
 */
public final class CommandContext {

    private final List<Path> javaFiles;
    private final String rootPath;
    private final ProjectConfig config;
    private final OutputFormatter formatter;
    private final IndexedCodebase indexed;
    private final CodeParser parser;
    private final boolean mdOutput;
    private final String outDir;

    private CallGraph callGraphCache;
    private DependencyAnalyzer dependencyAnalyzerCache;
    private SourceReader sourceReaderCache;

    public CommandContext(List<Path> javaFiles, String rootPath, ProjectConfig config,
                          OutputFormatter formatter, IndexedCodebase indexed, CodeParser parser) {
        this(javaFiles, rootPath, config, formatter, indexed, parser, false, null);
    }

    public CommandContext(List<Path> javaFiles, String rootPath, ProjectConfig config,
                          OutputFormatter formatter, IndexedCodebase indexed, CodeParser parser,
                          boolean mdOutput, String outDir) {
        this.javaFiles = javaFiles;
        this.rootPath = rootPath;
        this.config = config;
        this.formatter = formatter;
        this.indexed = indexed;
        this.parser = parser;
        this.mdOutput = mdOutput;
        this.outDir = outDir;
    }

    public List<Path> javaFiles() { return javaFiles; }
    public String rootPath() { return rootPath; }
    public ProjectConfig config() { return config; }
    public OutputFormatter formatter() { return formatter; }
    public IndexedCodebase indexed() { return indexed; }
    public CodeParser parser() { return parser; }
    public boolean mdOutput() { return mdOutput; }
    public String outDir() { return outDir; }

    private java.util.Map<String, String> qualifiedNameCache;

    /**
     * Resolves a simple class name to its fully qualified name.
     * Returns the input unchanged if not found in the index.
     */
    public String qualify(String simpleName) {
        if (simpleName == null || simpleName.contains(".")) return simpleName;
        if (qualifiedNameCache == null) {
            qualifiedNameCache = new java.util.HashMap<>();
            for (var ci : getAllClasses()) {
                qualifiedNameCache.putIfAbsent(ci.name(), ci.qualifiedName());
            }
        }
        return qualifiedNameCache.getOrDefault(simpleName, simpleName);
    }

    /**
     * Returns all classes, using index if available, parsing on-the-fly otherwise.
     */
    public List<ClassInfo> getAllClasses() {
        if (indexed != null) return indexed.getAllClasses();
        List<ClassInfo> all = new ArrayList<>();
        for (Path file : javaFiles) all.addAll(parser.parseClasses(file));
        return all;
    }

    /**
     * Returns a shared DependencyAnalyzer instance.
     */
    public DependencyAnalyzer dependencyAnalyzer() {
        if (dependencyAnalyzerCache == null) {
            dependencyAnalyzerCache = new DependencyAnalyzer();
        }
        return dependencyAnalyzerCache;
    }

    /**
     * Returns a shared SourceReader instance.
     */
    public SourceReader sourceReader() {
        if (sourceReaderCache == null) {
            sourceReaderCache = new SourceReader(parser);
        }
        return sourceReaderCache;
    }

    /**
     * Returns a shared, lazily-built call graph.
     * Uses the index if available (fast), falls back to full file parsing.
     * The same instance is returned on subsequent calls.
     */
    public CallGraph callGraph() {
        if (callGraphCache != null) return callGraphCache;

        var builder = new CallGraphBuilder();
        if (indexed != null && indexed.hasCallEdges()) {
            builder.loadFromIndex(indexed.getEntries());
        } else {
            builder.build(javaFiles);
        }
        callGraphCache = builder.toCallGraph();
        return callGraphCache;
    }
}
