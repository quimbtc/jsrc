package com.jsrc.app.output;

import java.nio.file.Path;
import java.util.List;

import com.jsrc.app.parser.model.CallChain;
import com.jsrc.app.parser.model.ClassInfo;
import com.jsrc.app.parser.model.CodeSmell;
import com.jsrc.app.parser.model.MethodInfo;

/**
 * Contract for formatting CLI output.
 * Implementations decide the presentation format (text, JSON, etc.)
 * while the business logic remains format-agnostic.
 */
public interface OutputFormatter {

    /**
     * Prints method search results.
     *
     * @param methods    found methods
     * @param file       source file path
     * @param methodName searched method name
     */
    void printMethods(List<MethodInfo> methods, Path file, String methodName);

    /**
     * Prints code smell findings for a file.
     *
     * @param smells detected smells
     * @param file   source file path
     */
    void printSmells(List<CodeSmell> smells, Path file);

    /**
     * Prints class listing results.
     *
     * @param classes discovered classes
     * @param sourceRoot source root for relative paths
     */
    void printClasses(List<ClassInfo> classes, Path sourceRoot);

    /**
     * Prints class dependency results.
     *
     * @param result dependency data for the queried class
     */
    void printDependencies(DependencyResult result);

    /**
     * Prints class hierarchy results.
     *
     * @param result hierarchy data for the queried class
     */
    void printHierarchy(HierarchyResult result);

    /**
     * Prints annotation search results.
     *
     * @param matches found annotation matches
     */
    void printAnnotationMatches(List<AnnotationMatch> matches);

    /**
     * Prints a compact summary of a single class: metadata + method signatures.
     *
     * @param classInfo the class to summarize
     * @param file      source file path
     */
    void printClassSummary(ClassInfo classInfo, Path file);

    /**
     * Prints call chain analysis results.
     *
     * @param chains     discovered call chains
     * @param methodName target method name
     */
    void printCallChains(List<CallChain> chains, String methodName);

    /**
     * Factory method to create the appropriate formatter.
     *
     * @param json true for JSON output, false for human-readable text
     * @return formatter instance
     */
    static OutputFormatter create(boolean json) {
        return create(json, false);
    }

    /**
     * Factory method with signature-only option.
     *
     * @param json          true for JSON output, false for human-readable text
     * @param signatureOnly true to emit only method signatures (no bodies, annotations, etc.)
     * @return formatter instance
     */
    static OutputFormatter create(boolean json, boolean signatureOnly) {
        return json ? new JsonFormatter(signatureOnly) : new TextFormatter(signatureOnly);
    }
}
