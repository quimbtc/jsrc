package com.jsrc.app.command;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.jsrc.app.output.JsonWriter;

/**
 * Shows git history for a class: commits, authors, last modified, hot methods.
 */
public class HistoryCommand implements Command {
    private final String className;

    public HistoryCommand(String className) {
        this.className = className;
    }

    @Override
    public int execute(CommandContext ctx) {
        // Find the file for this class
        String fileName = className + ".java";
        Path targetFile = null;
        for (Path f : ctx.javaFiles()) {
            if (f.getFileName().toString().equals(fileName)) {
                targetFile = f;
                break;
            }
        }

        if (targetFile == null) {
            System.err.printf("File for class '%s' not found.%n", className);
            return 0;
        }

        try {
            // git log for the file
            ProcessBuilder pb = new ProcessBuilder(
                    "git", "log", "--format=%H|%ae|%aI|%s",
                    "--follow", targetFile.toString());
            pb.directory(new File(ctx.rootPath()));
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes()).trim();
            proc.waitFor();

            if (output.isEmpty()) {
                System.err.printf("No git history for '%s'.%n", className);
                return 0;
            }

            String[] lines = output.split("\n");
            int totalCommits = lines.length;
            Set<String> authors = new LinkedHashSet<>();
            String lastModified = "";
            List<String> recentMessages = new ArrayList<>();

            for (int i = 0; i < lines.length; i++) {
                String[] parts = lines[i].split("\\|", 4);
                if (parts.length >= 3) {
                    authors.add(parts[1]);
                    if (i == 0) lastModified = parts[2];
                    if (i < 5 && parts.length >= 4) recentMessages.add(parts[3]);
                }
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("className", className);
            result.put("file", targetFile.toString());
            result.put("totalCommits", totalCommits);
            result.put("lastModified", lastModified);
            result.put("authors", List.copyOf(authors));
            result.put("recentCommits", recentMessages);

            System.out.println(JsonWriter.toJson(result));
            return 1;
        } catch (Exception e) {
            System.err.printf("Error reading git history: %s%n", e.getMessage());
            return 0;
        }
    }
}
