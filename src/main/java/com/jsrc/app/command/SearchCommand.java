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
    private final String[] alternatives;

    public SearchCommand(String pattern) {
        this.pattern = pattern;
        // Support OR patterns: "TODO|FIXME" searches for either term
        this.alternatives = pattern.contains("|") ? pattern.split("\\|") : new String[]{pattern};
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
                    int patternIdx = findFirstMatch(line);
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

        if (!ctx.fullOutput() && results.size() > 30) {
            // Compact: summary by class + top 30 matches
            var byClass = new LinkedHashMap<String, Integer>();
            int inCode = 0, inComments = 0;
            for (var r : results) {
                String cls = (String) r.getOrDefault("className", "");
                if (!cls.isEmpty()) byClass.merge(cls, 1, Integer::sum);
                if (Boolean.TRUE.equals(r.get("inComment"))) inComments++;
                else inCode++;
            }
            var compact = new LinkedHashMap<String, Object>();
            compact.put("total", results.size());
            compact.put("inCode", inCode);
            compact.put("inComments", inComments);
            compact.put("classesMentioned", byClass.size());
            // Top 10 classes by mention count
            compact.put("topClasses", byClass.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(10)
                    .map(e -> Map.of("class", e.getKey(), "count", e.getValue()))
                    .toList());
            compact.put("matches", results.subList(0, 30));
            compact.put("truncated", true);
            compact.put("hint", "Use --full to see all " + results.size() + " matches");
            ctx.formatter().printResult(compact);
        } else {
            ctx.formatter().printResult(results);
        }
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
        match.put("class", enclosingClass);
        match.put("method", enclosingMethod);
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
     * Finds the first occurrence of any alternative pattern in the line.
     * Returns the index of the first match, or -1 if none found.
     */
    private int findFirstMatch(String line) {
        int earliest = -1;
        for (String alt : alternatives) {
            int idx = line.indexOf(alt.trim());
            if (idx >= 0 && (earliest < 0 || idx < earliest)) {
                earliest = idx;
            }
        }
        return earliest;
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
