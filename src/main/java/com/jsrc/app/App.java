package com.jsrc.app;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import com.jsrc.app.codebase.CodeBase;
import com.jsrc.app.codebase.CodeBaseLoader;
import com.jsrc.app.codebase.JavaCodeBase;
import com.jsrc.app.command.*;
import com.jsrc.app.exception.BadUsageException;
import com.jsrc.app.exception.JsrcException;
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
        try {
            int exitCode = run(args);
            if (exitCode != ExitCode.OK) {
                System.exit(exitCode);
            }
        } catch (JsrcException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(e.exitCode());
        }
    }

    /**
     * Core logic, separated from main() for testability.
     * Returns an exit code instead of calling System.exit().
     *
     * @throws JsrcException on structured errors
     */
    static int run(String[] args) {
        ParsedArgs parsed = CliBootstrap.parse(args);
        if (parsed == null) return ExitCode.OK;

        // Handle --help
        if ("--help".equals(parsed.command())) {
            printHelp();
            return ExitCode.OK;
        }

        // Handle --describe (no source root needed)
        if ("--describe".equals(parsed.command())) {
            handleDescribe(parsed);
            return ExitCode.OK;
        }

        OutputFormatter formatter = OutputFormatter.create(
                parsed.jsonOutput(), parsed.signatureOnly(), parsed.fields());

        // Load config
        var config = parsed.configPath() != null
                ? com.jsrc.app.config.ProjectConfig.loadFrom(Path.of(parsed.configPath())).orElse(null)
                : com.jsrc.app.config.ProjectConfig.load(Path.of(".")).orElse(null);

        // Load codebase — multi-module: collect files from all sourceRoots
        var loader = new CodeBaseLoader();
        var javaFiles = new java.util.ArrayList<Path>();
        if (config != null && config.sourceRoots().size() > 1) {
            // Multi-module: iterate all source roots
            for (String root : config.sourceRoots()) {
                Path rootPath = Path.of(root);
                if (!rootPath.isAbsolute()) rootPath = Path.of(".").resolve(root);
                if (java.nio.file.Files.isDirectory(rootPath)) {
                    javaFiles.addAll(loader.loadFilesFrom(rootPath.toString(), "java"));
                }
            }
        } else {
            CodeBase project = new JavaCodeBase(parsed.rootPath(), loader);
            javaFiles.addAll(project.getFiles());
        }

        // Apply excludes
        if (config != null && !config.excludes().isEmpty()) {
            javaFiles = new java.util.ArrayList<>(filterExcludes(javaFiles, config.excludes()));
        }

        // Build command context
        var parser = new HybridJavaParser();
        IndexedCodebase indexed = "--diff".equals(parsed.command()) ? null
                : IndexedCodebase.tryLoad(Paths.get(parsed.rootPath()), javaFiles);
        var ctx = new CommandContext(javaFiles, parsed.rootPath(), config, formatter, indexed, parser,
                parsed.mdOutput(), parsed.outDir(), parsed.fullOutput(), parsed.noTest());

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
            return ExitCode.NOT_FOUND;
        }
        return ExitCode.OK;
    }

    private static void handleDescribe(ParsedArgs parsed) {
        var argList = new java.util.ArrayList<>(parsed.remainingArgs());
        argList.remove("--describe");
        if (argList.isEmpty()) {
            CommandRegistry.describeAll(parsed.jsonOutput());
        } else {
            String target = argList.getFirst();
            if (!target.startsWith("--")) target = "--" + target;
            if (!CommandRegistry.describeCommand(target, parsed.jsonOutput())) {
                CommandRegistry.describeAll(parsed.jsonOutput());
                throw new BadUsageException("Unknown command: " + target);
            }
        }
    }

    private static Command resolveCommand(String command, List<String> argList, boolean mdOutput) {
        // Special case: --verify needs --spec
        if ("--verify".equals(command)) {
            String cls = requireArg(argList, "--verify", "class name");
            int specIdx = argList.indexOf("--spec");
            if (specIdx < 0 || specIdx + 1 >= argList.size()) {
                throw new BadUsageException("--verify requires --spec <path.md>");
            }
            return new VerifyCommand(cls, argList.get(specIdx + 1));
        }

        // Special case: --test-for may have --depth
        if ("--test-for".equals(command)) {
            String method = requireMethodArg(argList, "--test-for");
            int depth = extractDepth(argList);
            return new TestForCommand(method, depth);
        }

        // Special case: --call-chain may have output dir
        if ("--call-chain".equals(command)) {
            String method = requireMethodArg(argList, "--call-chain");
            // Output dir is the arg after the method name
            int methodIdx = argList.indexOf(method);
            String outDir = (methodIdx >= 0 && methodIdx + 1 < argList.size())
                    ? argList.get(methodIdx + 1) : "./call-chains";
            return new CallChainCommand(method, outDir);
        }

        // Commands that accept free-text (multi-word) arguments
        var textCommands = List.of("--find", "--context-for", "--scope");
        if (textCommands.contains(command)) {
            String textArg = extractTextArg(argList, command);
            return CommandFactory.create(command, textArg, mdOutput);
        }

        // Extract arg (next token after command)
        String arg = extractArg(argList, command);

        // Validate arg for commands that need identifiers
        // Skip validation for free-text commands (--search accepts any pattern including |)
        // Skip validation for flag-style args (e.g. --all for --smells)
        if (arg != null && command.startsWith("--") && !arg.startsWith("--")
                && !arg.contains(";")
                && !List.of("--search").contains(command)) {
            if (List.of("--callers", "--callees", "--read", "--call-chain", "--smells", "--validate", "--type-check", "--impact", "--checklist", "--test-for").contains(command)) {
                String err = InputValidator.validateMethodRef(arg, "argument");
                if (err != null) { throw new BadUsageException(err); }
            } else {
                String err = InputValidator.validateIdentifier(arg, "argument");
                if (err != null) { throw new BadUsageException(err); }
            }
        }

        // Use factory
        Command cmd = CommandFactory.create(command, arg, mdOutput);
        if (cmd != null) return cmd;

        // Non-flag = method search
        if (!command.startsWith("--")) return CommandFactory.createMethodSearch(command);

        throw new BadUsageException("Unknown command: " + command);
    }

    /**
     * Extracts a multi-word argument: all tokens after the command until the next --flag.
     * E.g. --find database connection errors --json → "database connection errors"
     */
    private static String extractTextArg(List<String> argList, String command) {
        int cmdIdx = argList.indexOf(command);
        if (cmdIdx < 0 || cmdIdx + 1 >= argList.size()) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = cmdIdx + 1; i < argList.size(); i++) {
            String token = argList.get(i);
            if (token.startsWith("--")) break;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(token);
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    private static int extractDepth(List<String> argList) {
        int idx = argList.indexOf("--depth");
        if (idx < 0 || idx + 1 >= argList.size()) return 1; // default
        String value = argList.get(idx + 1);
        if ("full".equalsIgnoreCase(value)) return Integer.MAX_VALUE;
        try {
            int depth = Integer.parseInt(value);
            if (depth < 0) throw new BadUsageException("--depth must be >= 0, got: " + depth);
            return depth;
        } catch (NumberFormatException e) {
            throw new BadUsageException("--depth must be a number or 'full', got: " + value);
        }
    }

    private static String extractArg(List<String> argList, String command) {
        int cmdIdx = argList.indexOf(command);
        if (cmdIdx >= 0 && cmdIdx + 1 < argList.size()) {
            String next = argList.get(cmdIdx + 1);
            if (!next.startsWith("--") || "--all".equals(next)) return next;
        }
        return null;
    }

    private static String requireArg(List<String> argList, String command, String label) {
        int argPos = argList.indexOf(command) + 1;
        if (argPos < argList.size()) {
            String value = argList.get(argPos);
            String error = InputValidator.validateIdentifier(value, label);
            if (error != null) {
                throw new BadUsageException(error);
            }
            return value;
        }
        throw new BadUsageException(command + " requires a " + label);
    }

    private static String requireMethodArg(List<String> argList, String command) {
        int argPos = argList.indexOf(command) + 1;
        if (argPos < argList.size()) {
            String value = argList.get(argPos);
            String error = InputValidator.validateMethodRef(value, "argument");
            if (error != null) {
                throw new BadUsageException(error);
            }
            return value;
        }
        throw new BadUsageException(command + " requires an argument");
    }

    private static void printHelp() {
        System.out.println("""
                jsrc — Java Source Code Navigator for AI Agents & Humans

                Usage: jsrc [source-root] <command> [args] [flags]

                FIRST RUN: jsrc --index  (builds persistent index, one-time)

                ── Discovery ──────────────────────────────────────────────
                  --overview                Codebase stats: files, classes, methods, packages
                  --classes                 List all classes, interfaces, enums, records
                  --packages                Package map with inter-package dependencies
                  --search <pattern>        Structured text search with class/method context
                  --find <description>      Semantic search using Java concept synonyms
                                            "database errors" → finds DataSourceExceptionHandler
                  --scope <keywords>        Find relevant classes for a task (multi-word)
                                            Ranks by keyword match, estimates token cost
                  --map                     Token-budget repo map ranked by class importance
                  <methodName>              Search for methods by name

                ── Class Analysis ─────────────────────────────────────────
                  --summary <class>         Class summary with method signatures
                  --mini <class>            Ultra-compact summary (<500 chars, ~120 tokens)
                                            Top-5 methods by callers, abbreviated package
                  --deps <class>            Dependencies: imports, fields, constructor params
                  --hierarchy <class>       Extends, implements, subclasses
                  --implements <iface>      All implementors of an interface
                  --annotations <name>      Classes/methods with annotation
                  --related <class>         Related classes ranked by coupling score
                  --smells <class|--all>    Code smell detection (9 rules)
                  --stats <class>           Code metrics: LOC, complexity, coupling
                  --explain <class>         Concise actionable summary
                  --similar <class>         Structurally similar classes
                  --unused                  Dead code detection
                  --hotspots                Top classes by caller count (coupling hotspots)

                ── Call Graph ─────────────────────────────────────────────
                  --callers <method>        Who calls this method?
                  --callees <method>        What does this method call?
                  --call-chain <method>     Full call chains + Mermaid diagrams
                  --impact <method>         Change impact: callers, transitive, risk level
                                            "Binder.bind → 15 direct, 29 transitive, risk=HIGH"
                  --test-for <method>       Find tests related to a method
                                            [--depth N|full]  Transitive depth (default: 1)

                ── AI Agent Tools ─────────────────────────────────────────
                  --validate <method>       Anti-hallucination: verify method exists
                                            Suggests closest match for typos/wrong names
                  --type-check <method>     Return type verification from index
                  --context-for <task>      Auto-plan: what jsrc commands to run (multi-word)
                                            "fix NPE in OrderService.validate" → 4-step plan
                  --checklist <method>      Step-by-step change guide with callers
                  --lint <class>            Pre-compile diagnostics: unknown types, dead code
                  --resolve <expr>          Resolve receiver type from context class fields
                                            "Controller.service.process" → service is OrderService

                ── Code Generation ────────────────────────────────────────
                  --style                   Project conventions in <300 chars (~75 tokens)
                                            logging, injection, naming, nulls, collections
                  --patterns                Deep convention analysis: frequencies, layer chains
                                            Auto-discovers naming patterns (Detail→Bean→DAO)
                  --snippet <pattern>       Code template from real codebase class
                                            service, repository, controller, config, handler...

                ── Architecture ───────────────────────────────────────────
                  --check [ruleId]          Architecture rule violations
                  --layer <name>            Classes in an architectural layer
                  --endpoints               REST endpoints (path, method, controller)
                  --drift                   Architecture + spec drift detection
                  --verify <class> --spec   Verify implementation vs Markdown spec
                  --breaking-changes <cls>  Impact via inheritance, interfaces, reflection
                  --complexity <class|--all> Cyclomatic complexity analysis
                  --entry-points            Application entry points (main, controllers, etc.)

                ── Source ─────────────────────────────────────────────────
                  --read <Class[.method]>   Read source code (method-level precision)
                  --context <class>         Full context package for reverse engineering
                  --contract <class>        Extract formal interface contract
                  --imports <class>         Who imports this class (impact analysis)

                ── Git ────────────────────────────────────────────────────
                  --changed                 Java files changed (vs HEAD)
                  --diff                    Files changed since last index
                  --history <class>         Git history for a class

                ── Index ──────────────────────────────────────────────────
                  --index                   Build/update persistent index
                                            Incremental: only re-parses changed files

                ── Advanced ───────────────────────────────────────────────
                  --batch                   Multiple queries from stdin (JSON array)
                  --watch                   Daemon mode: stdin/stdout JSON protocol
                  --describe [cmd] --json   Machine-readable command metadata

                ── Flags ──────────────────────────────────────────────────
                  --json                    JSON output (always use for agents)
                  --md                      Markdown output (--context)
                  --fields <f1,f2>          Filter output fields (saves tokens)
                  --full                    Full output (default is compact/token-efficient)
                  --no-test                 Exclude test classes from results
                  --signature-only          Method signatures only
                  --metrics                 Execution metrics on stderr
                  --config <path>           Use specific .jsrc.yaml

                ── Method References ──────────────────────────────────────
                  methodName                    All methods with that name
                  Class.method                  Specific class
                  Class.method(Type1,Type2)     Specific overload
                  Generics stripped automatically (HashMap<K,V> → HashMap)
                  Ambiguous? Returns candidates list for disambiguation.

                ── Workflows for Agents ───────────────────────────────────
                  Fix bug:     --read method → --mini class → --impact → --validate fix
                  Add feature: --scope keywords → --style → --snippet → --checklist
                  Understand:  --overview → --map → --mini class → --related
                  Change sig:  --impact method → --callers → --checklist

                ── Examples ───────────────────────────────────────────────
                  jsrc --index
                  jsrc --overview --json
                  jsrc --mini SpringApplication --json
                  jsrc --validate Binder.bind --json
                  jsrc --impact Binder.bind --json
                  jsrc --find database errors --json
                  jsrc --context-for fix NPE in OrderService.validate --json
                  jsrc --style --json
                  jsrc --patterns --json
                  jsrc --snippet service --json
                  jsrc --scope kafka --json
                  jsrc --map --json
                  jsrc --related Binder --json
                  jsrc --lint SpringApplication --json
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
