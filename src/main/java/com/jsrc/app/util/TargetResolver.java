package com.jsrc.app.util;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.jsrc.app.index.IndexedCodebase;

/**
 * Resolves a user-provided target string (class name, method ref, file path)
 * to matching files and/or method locations. Reusable across commands.
 */
public final class TargetResolver {

    private TargetResolver() {}

    /**
     * A resolved method location with class name, method name, and line range.
     */
    public record MethodMatch(String className, String methodName, int startLine, int endLine) {}

    /**
     * Result of resolving a target string.
     */
    public record TargetResult(
            List<Path> files,
            List<MethodMatch> methodMatches,
            Set<String> matchingClasses,
            boolean ambiguous
    ) {
        public boolean isEmpty() {
            return files.isEmpty() && methodMatches.isEmpty();
        }
    }

    /**
     * Finds files matching a target by exact class name, exact file name,
     * or path ending with the target.
     */
    public static List<Path> findFileMatches(List<Path> javaFiles, String target) {
        String cleanTarget = target.endsWith(".java")
                ? target.substring(0, target.length() - 5) : target;

        List<Path> matches = new ArrayList<>();
        for (Path file : javaFiles) {
            String fileName = file.getFileName().toString();
            String fileNameNoExt = fileName.replace(".java", "");

            if (fileNameNoExt.equals(cleanTarget)
                    || fileName.equals(target)
                    || file.toString().endsWith(target)) {
                matches.add(file);
            }
        }
        return matches;
    }

    /**
     * Resolves class names to their source files.
     */
    public static List<Path> resolveClassesToFiles(List<Path> javaFiles, Set<String> classNames) {
        List<Path> matches = new ArrayList<>();
        for (Path file : javaFiles) {
            String fileNameNoExt = file.getFileName().toString().replace(".java", "");
            if (classNames.contains(fileNameNoExt)) {
                matches.add(file);
            }
        }
        return matches;
    }

    /**
     * Resolves a parsed method reference against the index to find matching
     * methods with their line ranges.
     *
     * @return result with method matches, matching classes, and ambiguity flag
     */
    public static TargetResult resolveMethodInIndex(MethodResolver.MethodRef ref,
                                                     IndexedCodebase indexed) {
        List<MethodMatch> matches = new ArrayList<>();
        Set<String> matchingClasses = new LinkedHashSet<>();

        for (var entry : indexed.getEntries()) {
            for (var ic : entry.classes()) {
                for (var im : ic.methods()) {
                    if (!im.name().equals(ref.methodName())) continue;
                    if (ref.hasClassName() && !ic.name().equals(ref.className())) continue;
                    if (ref.hasParamTypes()) {
                        int paramCount = SignatureUtils.countParams(im.signature());
                        if (paramCount >= 0 && paramCount != ref.paramTypes().size()) continue;
                    }
                    matches.add(new MethodMatch(ic.name(), im.name(), im.startLine(), im.endLine()));
                    matchingClasses.add(ic.name());
                }
            }
        }

        boolean ambiguous = matchingClasses.size() > 1 && !ref.hasClassName();
        return new TargetResult(List.of(), matches, matchingClasses, ambiguous);
    }
}
