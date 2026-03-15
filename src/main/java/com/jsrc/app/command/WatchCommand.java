package com.jsrc.app.command;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.*;
import java.util.LinkedHashMap;
import java.util.Map;

import com.jsrc.app.index.IndexedCodebase;
import com.jsrc.app.output.JsonReader;
import com.jsrc.app.output.JsonWriter;

/**
 * Daemon mode: watches filesystem for changes and serves queries via stdin.
 * Maintains index in memory for instant responses.
 * <p>
 * Protocol: one JSON command per line on stdin, one JSON result per line on stdout.
 * Send {"command":"quit"} to exit.
 */
public class WatchCommand implements Command {
    @Override
    public int execute(CommandContext ctx) {
        System.err.println("jsrc watch mode started. Send JSON commands on stdin. {\"command\":\"quit\"} to exit.");

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> input = (Map<String, Object>) JsonReader.parse(line);
                    if (input == null) continue;

                    String command = (String) input.getOrDefault("command", "");
                    if ("quit".equals(command) || "exit".equals(command)) {
                        System.err.println("jsrc watch mode exiting.");
                        break;
                    }

                    String arg = (String) input.getOrDefault("arg", "");

                    // Refresh index if needed
                    var freshIndexed = IndexedCodebase.tryLoad(
                            Paths.get(ctx.rootPath()), ctx.javaFiles());
                    var freshCtx = new CommandContext(
                            ctx.javaFiles(), ctx.rootPath(), ctx.config(),
                            ctx.formatter(), freshIndexed, ctx.parser());

                    // Execute command
                    Command cmd = resolveWatchCommand(command, arg);
                    if (cmd == null) {
                        Map<String, Object> error = new LinkedHashMap<>();
                        error.put("error", "Unknown command: " + command);
                        System.out.println(JsonWriter.toJson(error));
                        System.out.flush();
                        continue;
                    }

                    // Capture output
                    var baos = new java.io.ByteArrayOutputStream();
                    var originalOut = System.out;
                    System.setOut(new java.io.PrintStream(baos));
                    int resultCount = cmd.execute(freshCtx);
                    System.setOut(originalOut);

                    String output = baos.toString().trim();
                    // Forward output directly (already JSON)
                    System.out.println(output);
                    System.out.flush();

                } catch (Exception e) {
                    Map<String, Object> error = new LinkedHashMap<>();
                    error.put("error", e.getMessage());
                    System.out.println(JsonWriter.toJson(error));
                    System.out.flush();
                }
            }
        } catch (IOException e) {
            System.err.println("Error reading stdin: " + e.getMessage());
        }
        return 0;
    }

    private static Command resolveWatchCommand(String command, String arg) {
        return switch (command) {
            case "overview" -> new OverviewCommand();
            case "classes" -> new ClassesCommand();
            case "summary" -> arg.isEmpty() ? null : new SummaryCommand(arg);
            case "hierarchy" -> arg.isEmpty() ? null : new HierarchyCommand(arg);
            case "implements" -> arg.isEmpty() ? null : new ImplementsCommand(arg);
            case "deps" -> arg.isEmpty() ? null : new DepsCommand(arg);
            case "annotations" -> arg.isEmpty() ? null : new AnnotationsCommand(arg);
            case "callers" -> arg.isEmpty() ? null : new CallersCommand(arg);
            case "callees" -> arg.isEmpty() ? null : new CalleesCommand(arg);
            case "read" -> arg.isEmpty() ? null : new ReadCommand(arg);
            case "search" -> arg.isEmpty() ? null : new SearchCommand(arg);
            case "imports" -> arg.isEmpty() ? null : new ImportsCommand(arg);
            case "explain" -> arg.isEmpty() ? null : new ExplainCommand(arg);
            case "packages" -> new PackagesCommand();
            case "endpoints" -> new EndpointsCommand();
            case "unused" -> new UnusedCommand();
            case "stats" -> arg.isEmpty() ? null : new MetricsCommand(arg);
            case "smells" -> new SmellsCommand();
            case "similar" -> arg.isEmpty() ? null : new SimilarCommand(arg);
            default -> null;
        };
    }
}
