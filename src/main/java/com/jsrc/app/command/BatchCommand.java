package com.jsrc.app.command;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.jsrc.app.output.JsonReader;
import com.jsrc.app.output.JsonWriter;

/**
 * Executes multiple queries in a single JVM invocation.
 * Reads JSON array of command strings from stdin.
 * Returns JSON array of results.
 */
public class BatchCommand implements Command {

    @Override
    @SuppressWarnings("unchecked")
    public int execute(CommandContext ctx) {
        try {
            String input = new String(System.in.readAllBytes()).trim();
            Object parsed = JsonReader.parse(input);
            if (!(parsed instanceof List<?> commands)) {
                System.err.println("Error: --batch expects JSON array on stdin");
                return 0;
            }

            List<Map<String, Object>> results = new ArrayList<>();
            for (Object cmdObj : commands) {
                String cmdStr = cmdObj.toString();
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("command", cmdStr);

                CapturedOutput captured = captureOutput(() -> {
                    String[] parts = cmdStr.trim().split("\\s+");
                    String command = parts[0];
                    String arg = parts.length > 1 ? parts[1] : null;
                    Command cmd = CommandFactory.create(command, arg, false);
                    if (cmd == null && !command.startsWith("--")) {
                        cmd = CommandFactory.createMethodSearch(command);
                    }
                    if (cmd != null) {
                        return cmd.execute(ctx);
                    }
                    return -1;
                });

                if (captured.returnValue >= 0) {
                    entry.put("resultCount", captured.returnValue);
                    try {
                        entry.put("result", JsonReader.parse(captured.text));
                    } catch (Exception e) {
                        entry.put("result", captured.text);
                    }
                } else {
                    entry.put("error", "Unknown command");
                }
                results.add(entry);
            }

            System.out.println(JsonWriter.toJson(results));
            return results.size();
        } catch (IOException e) {
            System.err.println("Error reading stdin: " + e.getMessage());
            return 0;
        }
    }

    private record CapturedOutput(String text, int returnValue) {}

    private static CapturedOutput captureOutput(java.util.function.IntSupplier action) {
        var originalOut = System.out;
        var baos = new java.io.ByteArrayOutputStream();
        try {
            System.setOut(new java.io.PrintStream(baos));
            int result = action.getAsInt();
            return new CapturedOutput(baos.toString().trim(), result);
        } catch (Exception e) {
            return new CapturedOutput(e.getMessage(), -1);
        } finally {
            System.setOut(originalOut); // ALWAYS restore
        }
    }
}
