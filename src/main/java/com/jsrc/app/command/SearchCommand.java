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

                for (int i = 0; i < lines.size(); i++) {
                    if (lines.get(i).contains(pattern)) {
                        int lineNum = i + 1;
                        Map<String, Object> match = new LinkedHashMap<>();
                        match.put("file", file.toString());
                        match.put("line", lineNum);
                        match.put("context", lines.get(i).trim());

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
                        results.add(match);
                    }
                }
            } catch (IOException e) {
                // skip unreadable files
            }
        }

        System.out.println(JsonWriter.toJson(results));
        return results.size();
    }
}
