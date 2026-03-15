package com.jsrc.app.command;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.jsrc.app.output.JsonWriter;
import com.jsrc.app.parser.model.ClassInfo;
import com.jsrc.app.parser.model.MethodInfo;

/**
 * Structured search: finds a text pattern and returns results
 * with class, method, line, and context. Not raw grep.
 */
public class SearchCommand implements Command {
    private final String pattern;

    public SearchCommand(String pattern) {
        this.pattern = pattern;
    }

    @Override
    public int execute(CommandContext ctx) {
        List<Map<String, Object>> results = new ArrayList<>();

        for (Path file : ctx.javaFiles()) {
            try {
                List<String> lines = Files.readAllLines(file);
                List<ClassInfo> classes = ctx.parser().parseClasses(file);
                boolean inBlockComment = false;

                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    String trimmed = line.trim();

                    // Track block comment state
                    if (inBlockComment) {
                        if (trimmed.contains("*/")) {
                            int endIdx = trimmed.indexOf("*/");
                            // Check if pattern is before the closing */
                            boolean matchBeforeClose = line.contains(pattern)
                                    && line.indexOf(pattern) <= line.indexOf("*/");
                            inBlockComment = false;
                            if (line.contains(pattern)) {
                                addMatch(results, file, i + 1, line, classes, matchBeforeClose);
                            }
                        } else if (line.contains(pattern)) {
                            addMatch(results, file, i + 1, line, classes, true);
                        }
                    } else {
                        // Check if a block comment starts on this line
                        if (trimmed.startsWith("/*") || trimmed.startsWith("/**")) {
                            if (trimmed.contains("*/")) {
                                // Single-line block comment: /* ... */
                                if (line.contains(pattern)) {
                                    boolean patternInComment = isPatternInSingleLineBlockComment(line, pattern);
                                    addMatch(results, file, i + 1, line, classes, patternInComment);
                                }
                            } else {
                                inBlockComment = true;
                                if (line.contains(pattern)) {
                                    addMatch(results, file, i + 1, line, classes, true);
                                }
                            }
                        } else if (line.contains(pattern)) {
                            boolean isComment = isInLineComment(line, pattern);
                            addMatch(results, file, i + 1, line, classes, isComment);
                        }
                    }
                }
            } catch (IOException e) {
                // skip unreadable files
            }
        }

        ctx.formatter().printResult(results);
        return results.size();
    }

    private void addMatch(List<Map<String, Object>> results, Path file,
                          int lineNum, String line, List<ClassInfo> classes, boolean inComment) {
        Map<String, Object> match = new LinkedHashMap<>();
        match.put("file", file.toString());
        match.put("line", lineNum);
        match.put("context", line.trim());

        // Find enclosing class and method
        String enclosingClass = "";
        String enclosingMethod = "";
        for (ClassInfo ci : classes) {
            if (lineNum >= ci.startLine() && lineNum <= ci.endLine()) {
                enclosingClass = ci.name();
                for (MethodInfo m : ci.methods()) {
                    if (lineNum >= m.startLine() && lineNum <= m.endLine()) {
                        enclosingMethod = m.name();
                        break;
                    }
                }
                break;
            }
        }
        match.put("className", enclosingClass);
        match.put("methodName", enclosingMethod);
        match.put("inComment", inComment);
        results.add(match);
    }

    /**
     * Checks if the pattern appears after a // comment marker on the same line.
     */
    private static boolean isInLineComment(String line, String pattern) {
        int commentIdx = line.indexOf("//");
        if (commentIdx < 0) return false;
        int patternIdx = line.indexOf(pattern);
        // Pattern is in comment if // appears before the pattern
        // and the // is not inside a string literal
        return patternIdx > commentIdx && !isInsideStringLiteral(line, commentIdx);
    }

    /**
     * Checks if the pattern is inside a single-line block comment like /* ... *​/
     */
    private static boolean isPatternInSingleLineBlockComment(String line, String pattern) {
        int openIdx = line.indexOf("/*");
        int closeIdx = line.indexOf("*/");
        int patternIdx = line.indexOf(pattern);
        return openIdx >= 0 && closeIdx > openIdx && patternIdx > openIdx && patternIdx < closeIdx;
    }

    /**
     * Basic check: is the given index inside a string literal?
     * Counts unescaped quotes before the index.
     */
    private static boolean isInsideStringLiteral(String line, int index) {
        int quoteCount = 0;
        for (int i = 0; i < index; i++) {
            if (line.charAt(i) == '"' && (i == 0 || line.charAt(i - 1) != '\\')) {
                quoteCount++;
            }
        }
        return quoteCount % 2 != 0;
    }
}
