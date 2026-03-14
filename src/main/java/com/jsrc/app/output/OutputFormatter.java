package com.jsrc.app.output;

import java.nio.file.Path;
import java.util.List;

import com.jsrc.app.parser.model.CallChain;
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
        return json ? new JsonFormatter() : new TextFormatter();
    }
}
