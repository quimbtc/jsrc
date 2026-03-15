package com.jsrc.app.command;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger logger = LoggerFactory.getLogger(SearchCommand.class);

    @Override
    public int execute(CommandContext ctx) {
        List<Map<String, Object>> results = new ArrayList<>();

        // Note: index is NOT used to filter files — it doesn't contain method bodies,
        // so call sites would be missed. Index is only used below to avoid re-parsing
        // ClassInfo when resolving enclosing class/method context.
        List<Path> filesToSearch = ctx.javaFiles();

        for (Path file : filesToSearch) {
            try {
                List<String> lines = Files.readAllLines(file);
                // Use indexed class info when available to avoid re-parsing
                List<ClassInfo> classes = (ctx.indexed() != null)
                        ? ctx.indexed().findClassesInFile(file.toString())
                        : ctx.parser().parseClasses(file);
                boolean inBlockComment = false;

                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    int patternIdx = line.indexOf(pattern);
                    if (patternIdx < 0) {
                        // No match — just update block comment state
                        inBlockComment = updateBlockCommentState(line, inBlockComment);
                        continue;
                    }

                    boolean patternInComment = isPositionInComment(line, patternIdx, inBlockComment);
                    inBlockComment = updateBlockCommentState(line, inBlockComment);
                    addMatch(results, file, i + 1, line, classes, patternInComment);
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
     * Determines if a given position in a line is inside a comment,
     * considering the block comment state from previous lines.
     * Handles //, /* *​/, and transitions on the same line.
     */
    private static boolean isPositionInComment(String line, int position, boolean inBlock) {
        boolean inString = false;
        boolean currentlyInBlock = inBlock;

        for (int i = 0; i < line.length() && i < position; i++) {
            char c = line.charAt(i);

            if (currentlyInBlock) {
                if (c == '*' && i + 1 < line.length() && line.charAt(i + 1) == '/') {
                    currentlyInBlock = false;
                    i++; // skip '/'
                }
                continue;
            }

            // Track string literals
            if (c == '"' && (i == 0 || line.charAt(i - 1) != '\\')) {
                inString = !inString;
                continue;
            }
            if (inString) continue;

            // Line comment — everything after is comment
            if (c == '/' && i + 1 < line.length() && line.charAt(i + 1) == '/') {
                return true;
            }

            // Block comment start
            if (c == '/' && i + 1 < line.length() && line.charAt(i + 1) == '*') {
                currentlyInBlock = true;
                i++; // skip '*'
            }
        }

        return currentlyInBlock;
    }

    /**
     * Updates the block comment state after processing a full line.
     * Returns the inBlockComment state for the next line.
     */
    private static boolean updateBlockCommentState(String line, boolean inBlock) {
        boolean inString = false;
        boolean currentlyInBlock = inBlock;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);

            if (currentlyInBlock) {
                if (c == '*' && i + 1 < line.length() && line.charAt(i + 1) == '/') {
                    currentlyInBlock = false;
                    i++;
                }
                continue;
            }

            if (c == '"' && (i == 0 || line.charAt(i - 1) != '\\')) {
                inString = !inString;
                continue;
            }
            if (inString) continue;

            if (c == '/' && i + 1 < line.length() && line.charAt(i + 1) == '/') {
                break; // rest of line is comment, but doesn't affect next line
            }

            if (c == '/' && i + 1 < line.length() && line.charAt(i + 1) == '*') {
                currentlyInBlock = true;
                i++;
            }
        }

        return currentlyInBlock;
    }

    /**
     * Checks if the pattern looks like a Java identifier (class name, method name).
     * Index-based filtering only makes sense for identifiers, not arbitrary text.
     */
    private static boolean isJavaIdentifier(String pattern) {
        if (pattern == null || pattern.isEmpty()) return false;
        if (!Character.isJavaIdentifierStart(pattern.charAt(0)) && pattern.charAt(0) != '_') return false;
        for (int i = 1; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (!Character.isJavaIdentifierPart(c) && c != '.') return false;
        }
        return true;
    }
}
