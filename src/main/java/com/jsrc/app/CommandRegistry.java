package com.jsrc.app;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.jsrc.app.output.JsonWriter;

/**
 * Registry of all jsrc commands with their metadata.
 * Used by --describe for runtime introspection.
 */
public final class CommandRegistry {

    private CommandRegistry() {}

    private record CommandDef(
            String name,
            String description,
            List<String> args,
            List<String> flags,
            String outputType
    ) {}

    private static final List<CommandDef> COMMANDS = List.of(
            new CommandDef("--drift", "Combined architecture check + spec verification",
                    List.of(), List.of("--json", "--metrics"), "object"),
            new CommandDef("--verify", "Verify implementation against Markdown spec",
                    List.of("<className>", "--spec", "<path.md>"), List.of("--json", "--metrics"), "object"),
            new CommandDef("--contract", "Extract formal contract of an interface/class",
                    List.of("<className>"), List.of("--json", "--metrics"), "object"),
            new CommandDef("--changed", "Java files changed in git (vs HEAD)",
                    List.of(), List.of("--json", "--metrics"), "object"),
            new CommandDef("--context", "Full context package for a class (reverse engineering)",
                    List.of("<className>"), List.of("--json", "--md", "--metrics"), "object"),
            new CommandDef("--endpoints", "List REST endpoints (path, method, controller)",
                    List.of(), List.of("--json", "--metrics"), "array"),
            new CommandDef("--check", "Check architecture rules, report violations",
                    List.of("[ruleId]"), List.of("--json", "--metrics"), "object"),
            new CommandDef("--layer", "List classes in an architectural layer",
                    List.of("<layerName>"), List.of("--json", "--fields", "--metrics"), "array"),
            new CommandDef("--diff", "Show files changed since last index",
                    List.of(), List.of("--json", "--metrics"), "object"),
            new CommandDef("--callers", "Find all methods that call a given method",
                    List.of("<methodName>"), List.of("--json", "--metrics"), "array"),
            new CommandDef("--callees", "Find all methods called by a given method",
                    List.of("<methodName>"), List.of("--json", "--metrics"), "array"),
            new CommandDef("--read", "Read source code of a class or method",
                    List.of("<Class> or <Class.method>"), List.of("--json", "--fields", "--metrics"), "object"),
            new CommandDef("--index", "Build or update the persistent codebase index",
                    List.of(), List.of("--metrics"), "none (stderr only)"),
            new CommandDef("--overview", "Codebase overview: files, classes, methods, packages",
                    List.of(), List.of("--json", "--metrics"), "object"),
            new CommandDef("--classes", "List all classes, interfaces, enums, records",
                    List.of(), List.of("--json", "--fields", "--metrics"), "array"),
            new CommandDef("--summary", "Compact class summary with method signatures",
                    List.of("<className>"), List.of("--json", "--fields", "--metrics"), "object"),
            new CommandDef("--hierarchy", "Class hierarchy: extends, implements, subclasses",
                    List.of("<className>"), List.of("--json", "--metrics"), "object"),
            new CommandDef("--implements", "Find all implementors of an interface",
                    List.of("<interfaceName>"), List.of("--json", "--metrics"), "object"),
            new CommandDef("--deps", "Class dependencies: imports, fields, constructor params",
                    List.of("<className>"), List.of("--json", "--metrics"), "object"),
            new CommandDef("--annotations", "Find classes and methods with a specific annotation",
                    List.of("<annotationName>"), List.of("--json", "--fields", "--metrics"), "array"),
            new CommandDef("--smells", "Detect code smells across the codebase",
                    List.of(), List.of("--json", "--metrics"), "object per file"),
            new CommandDef("--call-chain", "Trace call chains to a method, generate Mermaid diagrams",
                    List.of("<methodName>", "[outputDir]"), List.of("--json", "--metrics"), "array"),
            new CommandDef("--search", "Structured text search with class/method context",
                    List.of("<pattern>"), List.of("--json", "--metrics"), "array"),
            new CommandDef("--imports", "Find all classes that import/depend on a class",
                    List.of("<className>"), List.of("--json", "--metrics"), "array"),
            new CommandDef("--packages", "Package map with inter-package dependencies",
                    List.of(), List.of("--json", "--metrics"), "array"),
            new CommandDef("--explain", "Concise actionable summary of a class",
                    List.of("<className>"), List.of("--json", "--metrics"), "object"),
            new CommandDef("--batch", "Execute multiple queries from stdin (JSON array)",
                    List.of(), List.of("--json"), "array"),
            new CommandDef("<methodName>", "Search for methods by name",
                    List.of(), List.of("--json", "--signature-only", "--fields", "--metrics"), "array"),
            new CommandDef("--describe", "List available commands and their metadata",
                    List.of("[commandName]"), List.of("--json"), "array or object")
    );

    /**
     * Returns all known command names (for input validation).
     */
    public static String[] knownCommandNames() {
        return COMMANDS.stream()
                .map(CommandDef::name)
                .filter(n -> n.startsWith("--"))
                .toArray(String[]::new);
    }

    /**
     * Prints all commands as JSON or text.
     */
    public static void describeAll(boolean json) {
        if (json) {
            List<Map<String, Object>> items = COMMANDS.stream()
                    .map(CommandRegistry::toMap)
                    .toList();
            System.out.println(JsonWriter.toJson(items));
        } else {
            System.out.println("Available commands:");
            for (CommandDef cmd : COMMANDS) {
                System.out.printf("  %-20s %s%n", cmd.name(), cmd.description());
                if (!cmd.args().isEmpty()) {
                    System.out.printf("    args: %s%n", String.join(" ", cmd.args()));
                }
                System.out.printf("    flags: %s%n", String.join(" ", cmd.flags()));
            }
        }
    }

    /**
     * Prints detail for a specific command.
     */
    public static boolean describeCommand(String commandName, boolean json) {
        for (CommandDef cmd : COMMANDS) {
            if (cmd.name().equals(commandName)) {
                if (json) {
                    System.out.println(JsonWriter.toJson(toMap(cmd)));
                } else {
                    System.out.printf("Command: %s%n", cmd.name());
                    System.out.printf("  Description: %s%n", cmd.description());
                    if (!cmd.args().isEmpty()) {
                        System.out.printf("  Args: %s%n", String.join(" ", cmd.args()));
                    }
                    System.out.printf("  Flags: %s%n", String.join(" ", cmd.flags()));
                    System.out.printf("  Output: %s%n", cmd.outputType());
                }
                return true;
            }
        }
        return false;
    }

    private static Map<String, Object> toMap(CommandDef cmd) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", cmd.name());
        map.put("description", cmd.description());
        map.put("args", cmd.args());
        map.put("flags", cmd.flags());
        map.put("outputType", cmd.outputType());
        return map;
    }
}
