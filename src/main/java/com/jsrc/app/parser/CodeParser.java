package com.jsrc.app.parser;

import java.nio.file.Path;
import java.util.List;

import com.jsrc.app.parser.model.ClassInfo;
import com.jsrc.app.parser.model.CodeSmell;
import com.jsrc.app.parser.model.MethodInfo;

/**
 * Contract for source code parsers that can extract structural
 * information from Java source files.
 */
public interface CodeParser {

    /**
     * Finds all methods with the given name in the specified file.
     */
    List<MethodInfo> findMethods(Path path, String methodName);

    /**
     * Finds methods with the given name and exact parameter types.
     */
    List<MethodInfo> findMethods(Path path, String methodName, List<String> parameterTypes);

    /**
     * Returns every method declared in the file.
     */
    List<MethodInfo> findAllMethods(Path path);

    /**
     * Parses all class/interface declarations in the file with their metadata.
     */
    List<ClassInfo> parseClasses(Path path);

    /**
     * Finds methods annotated with the given annotation name.
     *
     * @param annotationName simple name without '@' (e.g. "Override", "Test")
     */
    List<MethodInfo> findMethodsByAnnotation(Path path, String annotationName);

    /**
     * Detects common code smells in the given file.
     * Requires semantic AST analysis; syntax-only parsers return empty.
     */
    List<CodeSmell> detectSmells(Path path);

    /**
     * Returns the language this parser is configured for.
     */
    String getLanguage();

    /**
     * Returns files that were skipped due to encoding or I/O errors.
     */
    default java.util.Set<String> getSkippedFiles() {
        return java.util.Set.of();
    }
}
