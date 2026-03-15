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
            var originalOut = System.out;
            
            for (Object cmdObj : commands) {
                String cmdStr = cmdObj.toString();
                
                // Capture stdout
                var baos = new java.io.ByteArrayOutputStream();
                System.setOut(new java.io.PrintStream(baos));
                
                try {
                    // Parse the command string into args
                    String[] parts = cmdStr.trim().split("\\s+");
                    String command = parts[0];
                    List<String> argList = new ArrayList<>(List.of(parts));
                    
                    // Create and execute command
                    Command cmd = resolveFromString(command, argList);
                    int count = cmd.execute(ctx);
                    
                    String output = baos.toString().trim();
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("command", cmdStr);
                    entry.put("resultCount", count);
                    
                    // Try to parse output as JSON
                    try {
                        Object jsonOutput = JsonReader.parse(output);
                        entry.put("result", jsonOutput);
                    } catch (Exception e) {
                        entry.put("result", output);
                    }
                    
                    results.add(entry);
                } catch (Exception e) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("command", cmdStr);
                    entry.put("error", e.getMessage());
                    results.add(entry);
                } finally {
                    System.setOut(originalOut);
                }
            }

            System.out.println(JsonWriter.toJson(results));
            return results.size();
        } catch (IOException e) {
            System.err.println("Error reading stdin: " + e.getMessage());
            return 0;
        }
    }

    private static Command resolveFromString(String command, List<String> argList) {
        String arg = argList.size() > 1 ? argList.get(1) : null;
        return switch (command) {
            case "--overview" -> new OverviewCommand();
            case "--classes" -> new ClassesCommand();
            case "--smells" -> new SmellsCommand();
            case "--summary" -> new SummaryCommand(arg);
            case "--hierarchy" -> new HierarchyCommand(arg);
            case "--implements" -> new ImplementsCommand(arg);
            case "--deps" -> new DepsCommand(arg);
            case "--annotations" -> new AnnotationsCommand(arg);
            case "--callers" -> new CallersCommand(arg);
            case "--callees" -> new CalleesCommand(arg);
            case "--read" -> new ReadCommand(arg);
            case "--imports" -> new ImportsCommand(arg);
            case "--explain" -> new ExplainCommand(arg);
            case "--search" -> new SearchCommand(arg);
            case "--packages" -> new PackagesCommand();
            case "--endpoints" -> new EndpointsCommand();
            default -> new MethodSearchCommand(command);
        };
    }
}
