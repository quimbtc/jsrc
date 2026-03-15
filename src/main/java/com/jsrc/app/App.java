package com.jsrc.app;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import com.jsrc.app.codebase.CodeBase;
import com.jsrc.app.codebase.CodeBaseLoader;
import com.jsrc.app.codebase.JavaCodeBase;
import com.jsrc.app.command.*;
import com.jsrc.app.model.ExecutionMetrics;
import com.jsrc.app.output.JsonWriter;
import com.jsrc.app.output.OutputFormatter;
import com.jsrc.app.parser.HybridJavaParser;
import com.jsrc.app.util.InputValidator;
import com.jsrc.app.util.StopWatch;
import com.jsrc.app.index.IndexedCodebase;

/**
 * CLI entry point for jsrc — Java source code analysis tool.
 * Delegates argument parsing to {@link CliBootstrap} and dispatches to
 * {@link Command} implementations.
 */
public class App {

    public static void main(String[] args) {
        ParsedArgs parsed = CliBootstrap.parse(args);
        if (parsed == null) return;

        // Handle --help
        if ("--help".equals(parsed.command())) {
            printHelp();
            return;
        }

        // Handle --describe (no source root needed)
        if ("--describe".equals(parsed.command())) {
            handleDescribe(parsed);
            return;
        }

        OutputFormatter formatter = OutputFormatter.create(
                parsed.jsonOutput(), parsed.signatureOnly(), parsed.fields());

        // Load codebase
        CodeBase project = new JavaCodeBase(parsed.rootPath(), new CodeBaseLoader());
        List<Path> javaFiles = project.getFiles();

        // Apply excludes from config
        var config = parsed.configPath() != null
                ? com.jsrc.app.config.ProjectConfig.loadFrom(Path.of(parsed.configPath()))
                : com.jsrc.app.config.ProjectConfig.load(Path.of("."));
        if (config != null && !config.excludes().isEmpty()) {
            javaFiles = filterExcludes(javaFiles, config.excludes());
        }

        // Build command context
        var parser = new HybridJavaParser();
        IndexedCodebase indexed = "--diff".equals(parsed.command()) ? null
                : IndexedCodebase.tryLoad(Paths.get(parsed.rootPath()), javaFiles);
        var ctx = new CommandContext(javaFiles, parsed.rootPath(), config, formatter, indexed, parser);

        // Dispatch
        var timer = StopWatch.start();
        Command cmd = resolveCommand(parsed.command(), parsed.remainingArgs(), parsed.mdOutput());
        int resultCount = cmd.execute(ctx);

        // Metrics
        if (parsed.showMetrics()) {
            var metrics = new ExecutionMetrics(parsed.command(), timer.elapsedMs(),
                    javaFiles.size(), resultCount);
            if (parsed.jsonOutput()) {
                System.err.println(JsonWriter.toJson(metrics.toMap()));
            } else {
                System.err.println(metrics);
            }
        }

        // Report skipped files summary
        var skipped = parser.getSkippedFiles();
        if (!skipped.isEmpty()) {
            System.err.printf("Warning: skipped %d file(s) due to encoding errors%n", skipped.size());
        }

        // Exit code
        if (resultCount == 0 && !"--index".equals(parsed.command())) {
            System.exit(ExitCode.NOT_FOUND);
        }
    }

    private static void handleDescribe(ParsedArgs parsed) {
        var argList = parsed.remainingArgs();
        argList.remove("--describe");
        if (argList.isEmpty()) {
            CommandRegistry.describeAll(parsed.jsonOutput());
        } else {
            String target = argList.getFirst();
            if (!target.startsWith("--")) target = "--" + target;
            if (!CommandRegistry.describeCommand(target, parsed.jsonOutput())) {
                System.err.println("Unknown command: " + target);
                CommandRegistry.describeAll(parsed.jsonOutput());
                System.exit(ExitCode.BAD_USAGE);
            }
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
        System.exit(ExitCode.BAD_USAGE);
        return null;
    }

    private static void printHelp() {
        System.out.println("""
                jsrc — Java Source Code Navigator

                Usage: jsrc [source-root] <command> [args] [flags]

                Discovery:
                  --overview                Codebase overview: files, classes, methods, packages
                  --classes                 List all classes, interfaces, enums, records
                  --packages                Package map with inter-package dependencies
                  --search <pattern>        Structured text search with class/method context
                  <methodName>              Search for methods by name

                Analysis:
                  --summary <class>         Compact class summary with method signatures
                  --deps <class>            Class dependencies: imports, fields, params
                  --hierarchy <class>       Extends, implements, subclasses
                  --implements <iface>      Find all implementors of an interface
                  --annotations <name>      Find classes/methods with annotation
                  --smells                  Detect code smells
                  --stats <class>           Code metrics: LOC, complexity, coupling
                  --explain <class>         Concise actionable summary
                  --similar <class>         Find structurally similar classes
                  --unused                  Detect dead code

                Call Graph:
                  --callers <method>        Find all callers of a method
                  --callees <method>        Find all callees of a method
                  --call-chain <method>     Trace call chains, generate Mermaid diagrams

                Architecture:
                  --check [ruleId]          Check architecture rules
                  --layer <name>            List classes in a layer
                  --endpoints               List REST endpoints
                  --drift                   Combined architecture + spec check
                  --verify <class> --spec <md>  Verify against spec

                Source:
                  --read <Class[.method]>   Read source code
                  --context <class>         Full context package for reverse engineering
                  --contract <class>        Extract formal contract
                  --imports <class>         Find all classes that import a class

                Git:
                  --changed                 Java files changed (vs HEAD)
                  --diff                    Files changed since last index
                  --history <class>         Git history for a class

                Index:
                  --index                   Build/update persistent codebase index

                Advanced:
                  --batch                   Execute multiple queries from stdin
                  --watch                   Daemon mode: serve via stdin JSON
                  --describe [cmd] --json   Machine-readable command metadata

                Global Flags:
                  --json                    JSON output
                  --md                      Markdown output (some commands)
                  --fields <f1,f2>          Filter output fields
                  --signature-only          Method signatures only
                  --metrics                 Show execution metrics
                  --config <path>           Use specific config file

                Examples:
                  jsrc src/main/java --overview --json
                  jsrc --classes --fields name,packageName --json
                  jsrc --callers processOrder --json
                  jsrc --smells --json
                """);
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
}
