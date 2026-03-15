package com.jsrc.app;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.jsrc.app.codebase.CodeBase;
import com.jsrc.app.codebase.CodeBaseLoader;
import com.jsrc.app.codebase.JavaCodeBase;
import com.jsrc.app.command.*;
import com.jsrc.app.config.ProjectConfig;
import com.jsrc.app.index.IndexedCodebase;
import com.jsrc.app.output.ExecutionMetrics;
import com.jsrc.app.output.FieldsFilter;
import com.jsrc.app.output.JsonWriter;
import com.jsrc.app.output.OutputFormatter;
import com.jsrc.app.parser.HybridJavaParser;
import com.jsrc.app.util.InputValidator;
import com.jsrc.app.util.StopWatch;

/**
 * CLI entry point for jsrc — Java source code analysis tool.
 * Parses arguments and dispatches to Command implementations.
 */
public class App {

    public static void main(String[] args) {
        // Extract global flags
        List<String> argList = new ArrayList<>(List.of(args));
        boolean jsonOutput = argList.remove("--json");
        boolean mdOutput = argList.remove("--md");
        boolean signatureOnly = argList.remove("--signature-only");
        boolean showMetrics = argList.remove("--metrics");

        // Extract --fields <list>
        Set<String> fields = null;
        int fieldsIdx = argList.indexOf("--fields");
        if (fieldsIdx >= 0 && fieldsIdx + 1 < argList.size()) {
            fields = FieldsFilter.parseFields(argList.get(fieldsIdx + 1));
            argList.remove(fieldsIdx + 1);
            argList.remove(fieldsIdx);
        }

        // Extract --config <path>
        String configPath = null;
        int configIdx = argList.indexOf("--config");
        if (configIdx >= 0 && configIdx + 1 < argList.size()) {
            configPath = argList.get(configIdx + 1);
            argList.remove(configIdx + 1);
            argList.remove(configIdx);
        }

        // Handle --describe (no source root needed)
        if (argList.contains("--describe")) {
            argList.remove("--describe");
            if (argList.isEmpty()) {
                CommandRegistry.describeAll(jsonOutput);
            } else {
                String target = argList.getFirst();
                if (!target.startsWith("--")) target = "--" + target;
                if (!CommandRegistry.describeCommand(target, jsonOutput)) {
                    System.err.println("Unknown command: " + target);
                    CommandRegistry.describeAll(jsonOutput);
                    System.exit(ExitCode.BAD_USAGE);
                }
            }
            return;
        }

        OutputFormatter formatter = OutputFormatter.create(jsonOutput, signatureOnly, fields);

        // Load project config
        ProjectConfig config = configPath != null
                ? ProjectConfig.loadFrom(Path.of(configPath))
                : ProjectConfig.load(Path.of("."));

        // Resolve source root: explicit arg > config sourceRoots > pwd
        // Convention: if first arg starts with "--", it's a command (not a path).
        String rootPath;
        String command;
        if (argList.size() >= 2 && argList.get(0).startsWith("--")) {
            rootPath = resolveRoot(config);
            command = argList.get(0);
        } else if (argList.size() >= 2) {
            rootPath = argList.get(0);
            command = argList.get(1);
        } else if (argList.size() == 1) {
            rootPath = resolveRoot(config);
            command = argList.get(0);
        } else {
            printUsage();
            System.exit(ExitCode.BAD_USAGE);
            return;
        }

        // Validate inputs
        String pathError = InputValidator.validatePath(rootPath, "Source root");
        if (pathError != null) {
            System.err.println("Error: " + pathError);
            System.exit(ExitCode.BAD_USAGE);
        }
        String cmdError = InputValidator.validateCommand(command);
        if (cmdError != null) {
            System.err.println("Error: " + cmdError);
            System.exit(ExitCode.BAD_USAGE);
        }

        // Load codebase
        CodeBase project = new JavaCodeBase(rootPath, new CodeBaseLoader());
        List<Path> javaFiles = project.getFiles();

        // Apply excludes from config
        if (config != null && !config.excludes().isEmpty()) {
            javaFiles = filterExcludes(javaFiles, config.excludes());
        }

        // Build command context
        var parser = new HybridJavaParser();

        // --diff must read raw index before auto-refresh
        IndexedCodebase indexed = "--diff".equals(command) ? null
                : IndexedCodebase.tryLoad(Paths.get(rootPath), javaFiles);

        var ctx = new CommandContext(javaFiles, rootPath, config, formatter, indexed, parser);

        // Dispatch
        var timer = StopWatch.start();
        Command cmd = resolveCommand(command, argList, mdOutput);
        int resultCount = cmd.execute(ctx);

        // Metrics
        if (showMetrics) {
            var metrics = new ExecutionMetrics(command, timer.elapsedMs(), javaFiles.size(), resultCount);
            if (jsonOutput) {
                System.err.println(JsonWriter.toJson(metrics.toMap()));
            } else {
                System.err.println(metrics);
            }
        }

        // Exit code
        if (resultCount == 0 && !"--index".equals(command)) {
            System.exit(ExitCode.NOT_FOUND);
        }
    }

    private static Command resolveCommand(String command, List<String> argList, boolean mdOutput) {
        // Special case: --verify needs --spec
        if ("--verify".equals(command)) {
            String cls = requireArg(argList, "--verify", "class name");
            int specIdx = argList.indexOf("--spec");
            if (specIdx < 0 || specIdx + 1 >= argList.size()) {
                System.err.println("Error: --verify requires --spec <path.md>");
                System.exit(ExitCode.BAD_USAGE);
            }
            return new VerifyCommand(cls, argList.get(specIdx + 1));
        }

        // Special case: --call-chain may have output dir
        if ("--call-chain".equals(command)) {
            String method = requireMethodArg(argList, "--call-chain");
            String outDir = argList.size() >= 4 ? argList.get(3) : "./call-chains";
            return new CallChainCommand(method, outDir);
        }

        // Extract arg (next token after command)
        String arg = extractArg(argList, command);

        // Validate arg for commands that need identifiers
        if (arg != null && command.startsWith("--")) {
            if (List.of("--callers", "--callees", "--read", "--search", "--call-chain").contains(command)) {
                String err = InputValidator.validateMethodRef(arg, "argument");
                if (err != null) { System.err.println("Error: " + err); System.exit(ExitCode.BAD_USAGE); }
            } else {
                String err = InputValidator.validateIdentifier(arg, "argument");
                if (err != null) { System.err.println("Error: " + err); System.exit(ExitCode.BAD_USAGE); }
            }
        }

        // Use factory
        Command cmd = CommandFactory.create(command, arg, mdOutput);
        if (cmd != null) return cmd;

        // Non-flag = method search
        if (!command.startsWith("--")) return CommandFactory.createMethodSearch(command);

        System.err.println("Unknown command: " + command);
        System.exit(ExitCode.BAD_USAGE);
        return null;
    }

    private static String extractArg(List<String> argList, String command) {
        int cmdIdx = argList.indexOf(command);
        if (cmdIdx >= 0 && cmdIdx + 1 < argList.size()) {
            String next = argList.get(cmdIdx + 1);
            if (!next.startsWith("--")) return next;
        }
        return null;
    }

    private static String requireArg(List<String> argList, String command, String label) {
        // arg is at position 2 (root, command, arg) or position 1 (command, arg)
        int argPos = argList.indexOf(command) + 1;
        if (argPos < argList.size()) {
            String value = argList.get(argPos);
            String error = InputValidator.validateIdentifier(value, label);
            if (error != null) {
                System.err.println("Error: " + error);
                System.exit(ExitCode.BAD_USAGE);
            }
            return value;
        }
        System.err.printf("Error: %s requires a %s%n", command, label);
        printUsage();
        System.exit(ExitCode.BAD_USAGE);
        return null;
    }

    private static String requireMethodArg(List<String> argList, String command) {
        int argPos = argList.indexOf(command) + 1;
        if (argPos < argList.size()) {
            String value = argList.get(argPos);
            String error = InputValidator.validateMethodRef(value, "argument");
            if (error != null) {
                System.err.println("Error: " + error);
                System.exit(ExitCode.BAD_USAGE);
            }
            return value;
        }
        System.err.printf("Error: %s requires an argument%n", command);
        printUsage();
        System.exit(ExitCode.BAD_USAGE);
        return null;
    }

    private static String resolveRoot(ProjectConfig config) {
        if (config != null && !config.sourceRoots().isEmpty()) {
            return config.sourceRoots().getFirst();
        }
        return ".";
    }

    private static List<Path> filterExcludes(List<Path> files, List<String> excludes) {
        return files.stream()
                .filter(f -> {
                    String pathStr = f.toString();
                    for (String pattern : excludes) {
                        String regex = pattern
                                .replace(".", "\\.")
                                .replace("**/", "(.*/)?")
                                .replace("**", ".*")
                                .replace("*", "[^/]*");
                        if (pathStr.matches(".*" + regex + ".*")) return false;
                    }
                    return true;
                })
                .toList();
    }

    private static void printUsage() {
        System.err.println("Usage: jsrc [source-root] <command> [args] [flags]");
        System.err.println("Run 'jsrc --describe --json' for all commands.");
    }
}
