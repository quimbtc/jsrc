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
                    Command cmd = CommandFactory.create("--" + command, arg, false);
                    if (cmd == null && !command.startsWith("--")) {
                        cmd = CommandFactory.createMethodSearch(command);
                    }
                    if (cmd == null) {
                        Map<String, Object> error = new LinkedHashMap<>();
                        error.put("error", "Unknown command: " + command);
                        System.out.println(JsonWriter.toJson(error));
                        System.out.flush();
                        continue;
                    }

                    // Capture output safely
                    var originalOut = System.out;
                    var baos = new java.io.ByteArrayOutputStream();
                    try {
                        System.setOut(new java.io.PrintStream(baos));
                        cmd.execute(freshCtx);
                    } finally {
                        System.setOut(originalOut); // ALWAYS restore
                    }
                    System.out.println(baos.toString().trim());
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


}
