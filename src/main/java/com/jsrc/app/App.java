package com.jsrc.app;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.jsrc.app.codebase.CodeBase;
import com.jsrc.app.codebase.CodeBaseLoader;
import com.jsrc.app.codebase.JavaCodeBase;
import com.jsrc.app.output.OutputFormatter;
import com.jsrc.app.parser.CallChainTracer;
import com.jsrc.app.parser.CallGraphBuilder;
import com.jsrc.app.parser.CodeParser;
import com.jsrc.app.parser.HybridJavaParser;
import com.jsrc.app.parser.MermaidDiagramGenerator;
import com.jsrc.app.output.AnnotationMatch;
import com.jsrc.app.output.DependencyResult;
import com.jsrc.app.output.HierarchyResult;
import com.jsrc.app.output.OverviewResult;
import com.jsrc.app.parser.model.AnnotationInfo;
import com.jsrc.app.parser.model.CallChain;
import com.jsrc.app.parser.model.ClassInfo;
import com.jsrc.app.parser.model.CodeSmell;
import com.jsrc.app.parser.model.MethodInfo;

/**
 * CLI entry point for jsrc — Java source code analysis tool.
 */
public class App {

    public static void main(String[] args) {
        List<String> argList = new ArrayList<>(List.of(args));
        boolean jsonOutput = argList.remove("--json");
        boolean mdOutput = argList.remove("--md");
        boolean signatureOnly = argList.remove("--signature-only");
        boolean showMetrics = argList.remove("--metrics");

        // Extract --fields <list>
        java.util.Set<String> fields = null;
        int fieldsIdx = argList.indexOf("--fields");
        if (fieldsIdx >= 0 && fieldsIdx + 1 < argList.size()) {
            fields = com.jsrc.app.output.FieldsFilter.parseFields(argList.get(fieldsIdx + 1));
            argList.remove(fieldsIdx + 1);
            argList.remove(fieldsIdx);
        }

        // Extract --config <path> if present
        String configPath = null;
        int configIdx = argList.indexOf("--config");
        if (configIdx >= 0 && configIdx + 1 < argList.size()) {
            configPath = argList.get(configIdx + 1);
            argList.remove(configIdx + 1);
            argList.remove(configIdx);
        }

        // Handle --describe before normal dispatch (doesn't need source root)
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

        // Try loading project config
        com.jsrc.app.config.ProjectConfig config = configPath != null
                ? com.jsrc.app.config.ProjectConfig.loadFrom(Path.of(configPath))
                : com.jsrc.app.config.ProjectConfig.load(Path.of("."));

        // Resolve source root: explicit arg > config sourceRoots > pwd
        // Convention: if first arg starts with "--", it's a command (not a path).
        // This works because source root paths never start with "--".
        // Examples:
        //   jsrc --overview          → root=pwd, command=--overview
        //   jsrc src --overview      → root=src, command=--overview
        //   jsrc --summary App       → root=pwd, command=--summary
        //   jsrc /tmp/proj --summary App → root=/tmp/proj, command=--summary
        String rootPath;
        String command;
        if (argList.size() >= 2 && argList.get(0).startsWith("--")) {
            // No root path provided, first arg is a command
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
        String pathError = com.jsrc.app.util.InputValidator.validatePath(rootPath, "Source root");
        if (pathError != null) {
            System.err.println("Error: " + pathError);
            System.exit(ExitCode.BAD_USAGE);
        }
        String cmdError = com.jsrc.app.util.InputValidator.validateCommand(command);
        if (cmdError != null) {
            System.err.println("Error: " + cmdError);
            System.exit(ExitCode.BAD_USAGE);
        }

        CodeBase project = new JavaCodeBase(rootPath, new CodeBaseLoader());
        List<Path> javaFiles = project.getFiles();

        // Apply excludes from config
        if (config != null && !config.excludes().isEmpty()) {
            javaFiles = filterExcludes(javaFiles, config.excludes());
        }

        var timer = com.jsrc.app.util.StopWatch.start();
        int[] resultCount = {0}; // mutable counter for lambdas

        // --diff must read raw index BEFORE auto-refresh
        if ("--changed".equals(command)) {
            resultCount[0] = runChanged(rootPath, formatter);
        } else if ("--diff".equals(command)) {
            resultCount[0] = runDiff(javaFiles, rootPath, formatter);
        } else {

        // Try loading index for full-parse commands (auto-refreshes stale entries)
        var indexedCodebase = com.jsrc.app.index.IndexedCodebase.tryLoad(Paths.get(rootPath), javaFiles);

        if ("--drift".equals(command)) {
            resultCount[0] = runDrift(indexedCodebase, javaFiles, rootPath, config, formatter);
        } else if ("--verify".equals(command)) {
            if (argList.size() < 3) {
                System.err.println("Error: --verify requires a class name and --spec path");
                printUsage();
                System.exit(ExitCode.BAD_USAGE);
            }
            String className = validateArg(argList.get(2), "Class name");
            // Find --spec arg
            int specIdx = argList.indexOf("--spec");
            if (specIdx < 0 || specIdx + 1 >= argList.size()) {
                System.err.println("Error: --verify requires --spec <path.md>");
                System.exit(ExitCode.BAD_USAGE);
            }
            String specPath = argList.get(specIdx + 1);
            resultCount[0] = runVerify(javaFiles, className, specPath);
        } else if ("--contract".equals(command)) {
            if (argList.size() < 3) {
                System.err.println("Error: --contract requires an interface/class name");
                printUsage();
                System.exit(ExitCode.BAD_USAGE);
            }
            String className = validateArg(argList.get(2), "Interface name");
            resultCount[0] = runContract(indexedCodebase, javaFiles, rootPath, className, formatter);
        } else if ("--context".equals(command)) {
            if (argList.size() < 3) {
                System.err.println("Error: --context requires a class name");
                printUsage();
                System.exit(ExitCode.BAD_USAGE);
            }
            String className = validateArg(argList.get(2), "Class name");
            resultCount[0] = runContext(javaFiles, rootPath, className, config, formatter, mdOutput);
        } else if ("--endpoints".equals(command)) {
            resultCount[0] = runEndpoints(indexedCodebase, javaFiles, rootPath, config, formatter);
        } else if ("--check".equals(command)) {
            resultCount[0] = runCheck(indexedCodebase, javaFiles, rootPath,
                    argList.size() >= 3 ? argList.get(2) : null, config, formatter);
        } else if ("--layer".equals(command)) {
            if (argList.size() < 3) {
                System.err.println("Error: --layer requires a layer name");
                printUsage();
                System.exit(ExitCode.BAD_USAGE);
            }
            String layerName = argList.get(2);
            resultCount[0] = runLayer(indexedCodebase, javaFiles, rootPath, layerName, config, formatter);
        } else if ("--callers".equals(command)) {
            if (argList.size() < 3) {
                System.err.println("Error: --callers requires a method name");
                printUsage();
                System.exit(ExitCode.BAD_USAGE);
            }
            String methodName = validateArg(argList.get(2), "Method name");
            resultCount[0] = runCallers(javaFiles, methodName, formatter);
        } else if ("--callees".equals(command)) {
            if (argList.size() < 3) {
                System.err.println("Error: --callees requires a method name");
                printUsage();
                System.exit(ExitCode.BAD_USAGE);
            }
            String methodName = validateArg(argList.get(2), "Method name");
            resultCount[0] = runCallees(javaFiles, methodName, formatter);
        } else if ("--read".equals(command)) {
            if (argList.size() < 3) {
                System.err.println("Error: --read requires Class or Class.method");
                printUsage();
                System.exit(ExitCode.BAD_USAGE);
            }
            String target = argList.get(2);
            CodeParser parser = new HybridJavaParser();
            resultCount[0] = runRead(parser, javaFiles, target, formatter);
        } else if ("--index".equals(command)) {
            CodeParser parser = new HybridJavaParser();
            runIndex(parser, javaFiles, rootPath);
        } else if ("--overview".equals(command)) {
            resultCount[0] = runOverview(indexedCodebase, javaFiles, rootPath, formatter);
        } else if ("--deps".equals(command)) {
            if (argList.size() < 3) {
                System.err.println("Error: --deps requires a class name");
                printUsage();
                System.exit(ExitCode.BAD_USAGE);
            }
            String className = validateArg(argList.get(2), "Class name");
            resultCount[0] = runDependencyAnalysis(javaFiles, className, formatter);
        } else if ("--implements".equals(command)) {
            if (argList.size() < 3) {
                System.err.println("Error: --implements requires an interface name");
                printUsage();
                System.exit(ExitCode.BAD_USAGE);
            }
            String ifaceName = validateArg(argList.get(2), "Interface name");
            resultCount[0] = runImplements(indexedCodebase, javaFiles, ifaceName, formatter);
        } else if ("--hierarchy".equals(command)) {
            if (argList.size() < 3) {
                System.err.println("Error: --hierarchy requires a class name");
                printUsage();
                System.exit(ExitCode.BAD_USAGE);
            }
            String className = validateArg(argList.get(2), "Class name");
            resultCount[0] = runHierarchy(indexedCodebase, javaFiles, className, formatter);
        } else if ("--summary".equals(command)) {
            if (argList.size() < 3) {
                System.err.println("Error: --summary requires a class name");
                printUsage();
                System.exit(ExitCode.BAD_USAGE);
            }
            String className = validateArg(argList.get(2), "Class name");
            resultCount[0] = runClassSummary(indexedCodebase, javaFiles, rootPath, className, formatter);
        } else if ("--annotations".equals(command)) {
            if (argList.size() < 3) {
                System.err.println("Error: --annotations requires an annotation name");
                printUsage();
                System.exit(ExitCode.BAD_USAGE);
            }
            String annotationName = validateArg(argList.get(2), "Annotation name");
            resultCount[0] = runAnnotationSearch(indexedCodebase, javaFiles, rootPath, annotationName, formatter);
        } else if ("--classes".equals(command)) {
            resultCount[0] = runClassListing(indexedCodebase, javaFiles, rootPath, formatter);
        } else if ("--smells".equals(command)) {
            CodeParser parser = new HybridJavaParser();
            resultCount[0] = runSmellDetection(parser, javaFiles, rootPath, formatter);
        } else if ("--call-chain".equals(command)) {
            if (argList.size() < 3) {
                System.err.println("Error: --call-chain requires a method name");
                printUsage();
                System.exit(ExitCode.BAD_USAGE);
            }
            String methodName = validateArg(argList.get(2), "Method name");
            String outputDir = argList.size() >= 4 ? argList.get(3) : "./call-chains";
            resultCount[0] = runCallChainAnalysis(javaFiles, rootPath, methodName, outputDir, formatter);
        } else {
            CodeParser parser = new HybridJavaParser();
            resultCount[0] = runMethodSearch(parser, javaFiles, rootPath, command, formatter);
        }

        } // end of else block (non-diff commands)

        if (showMetrics) {
            var metrics = new com.jsrc.app.output.ExecutionMetrics(
                    command, timer.elapsedMs(), javaFiles.size(), resultCount[0]);
            if (jsonOutput) {
                System.err.println(com.jsrc.app.output.JsonWriter.toJson(metrics.toMap()));
            } else {
                System.err.println(metrics);
            }
        }

        // Exit code: 0 = results found, 1 = no results
        if (resultCount[0] == 0 && !"--index".equals(command)) {
            System.exit(ExitCode.NOT_FOUND);
        }
    }

    private static int runDrift(com.jsrc.app.index.IndexedCodebase indexed,
                                     List<Path> javaFiles, String rootPath,
                                     com.jsrc.app.config.ProjectConfig config,
                                     OutputFormatter formatter) {
        Map<String, Object> report = new java.util.LinkedHashMap<>();

        // Architecture check
        if (config != null && !config.architecture().rules().isEmpty()) {
            List<ClassInfo> allClasses;
            if (indexed != null) {
                allClasses = indexed.getAllClasses();
            } else {
                CodeParser parser = new HybridJavaParser();
                allClasses = new ArrayList<>();
                for (Path file : javaFiles) allClasses.addAll(parser.parseClasses(file));
            }
            var engine = new com.jsrc.app.architecture.RuleEngine(config.architecture());
            var violations = engine.evaluate(allClasses, javaFiles);
            report.put("architectureViolations", violations.size());
            if (!violations.isEmpty()) {
                report.put("violations", violations.stream().map(v -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("ruleId", v.ruleId());
                    m.put("className", v.className());
                    m.put("message", v.message());
                    return m;
                }).toList());
            }
        } else {
            report.put("architectureViolations", 0);
        }

        // Changed files
        var diffResult = runDiffInternal(javaFiles, rootPath);
        report.put("changedFiles", diffResult.size());
        if (!diffResult.isEmpty()) report.put("changed", diffResult);

        int total = ((Number) report.get("architectureViolations")).intValue() + diffResult.size();
        report.put("totalIssues", total);

        System.out.println(com.jsrc.app.output.JsonWriter.toJson(report));
        return total;
    }

    private static List<String> runDiffInternal(List<Path> javaFiles, String rootPath) {
        Path root = Paths.get(rootPath);
        List<com.jsrc.app.index.IndexEntry> existing = com.jsrc.app.index.CodebaseIndex.load(root);
        if (existing.isEmpty()) return List.of();

        Map<String, com.jsrc.app.index.IndexEntry> byPath = new java.util.HashMap<>();
        for (var entry : existing) byPath.put(entry.path(), entry);

        List<String> modified = new ArrayList<>();
        for (Path file : javaFiles) {
            String relativePath = root.relativize(file).toString();
            var prev = byPath.get(relativePath);
            if (prev == null) {
                modified.add(relativePath);
            } else {
                try {
                    long currentModified = java.nio.file.Files.getLastModifiedTime(file).toMillis();
                    if (currentModified > prev.lastModified()) {
                        byte[] content = java.nio.file.Files.readAllBytes(file);
                        String hash = sha256(content);
                        if (!hash.equals(prev.contentHash())) {
                            modified.add(relativePath);
                        }
                    }
                } catch (java.io.IOException e) {
                    modified.add(relativePath);
                }
            }
        }
        return modified;
    }

    private static int runVerify(List<Path> javaFiles, String className, String specPath) {
        try {
            var spec = com.jsrc.app.spec.SpecParser.parse(Path.of(specPath));
            CodeParser parser = new HybridJavaParser();
            List<ClassInfo> allClasses = new ArrayList<>();
            for (Path file : javaFiles) allClasses.addAll(parser.parseClasses(file));

            ClassInfo ci = resolveClass(allClasses, className);
            if (ci == null) return 0;

            var result = com.jsrc.app.spec.SpecVerifier.verify(ci, spec);
            System.out.println(com.jsrc.app.output.JsonWriter.toJson(result));
            @SuppressWarnings("unchecked")
            List<?> discs = (List<?>) result.get("discrepancies");
            return discs.size();
        } catch (java.io.IOException e) {
            System.err.printf("Error reading spec: %s%n", e.getMessage());
            System.exit(ExitCode.IO_ERROR);
            return 0;
        }
    }

    private static int runContract(com.jsrc.app.index.IndexedCodebase indexed,
                                        List<Path> javaFiles, String rootPath,
                                        String className, OutputFormatter formatter) {
        List<ClassInfo> allClasses = getAllClasses(indexed, javaFiles);
        ClassInfo ci = resolveClass(allClasses, className);
        if (ci == null) return 0;
        formatter.printClassSummary(ci, Path.of(""));
        return 1;
    }

    private static int runContext(List<Path> javaFiles, String rootPath,
                                       String className,
                                       com.jsrc.app.config.ProjectConfig config,
                                       OutputFormatter formatter,
                                       boolean mdOutput) {
        CodeParser parser = new HybridJavaParser();
        List<ClassInfo> allClasses = new ArrayList<>();
        for (Path file : javaFiles) allClasses.addAll(parser.parseClasses(file));

        // Resolve with disambiguation
        ClassInfo resolved = resolveClass(allClasses, className);
        if (resolved == null) return 0;

        var arch = config != null ? config.architecture() : null;
        var assembler = new com.jsrc.app.parser.ContextAssembler(parser);
        Map<String, Object> ctx = assembler.assemble(javaFiles, resolved.name(), allClasses, arch);

        if (ctx == null) return 0;

        if (mdOutput) {
            System.out.println(com.jsrc.app.output.MarkdownFormatter.toMarkdown(ctx));
        } else {
            System.out.println(com.jsrc.app.output.JsonWriter.toJson(ctx));
        }
        return 1;
    }

    private static int runEndpoints(com.jsrc.app.index.IndexedCodebase indexed,
                                        List<Path> javaFiles, String rootPath,
                                        com.jsrc.app.config.ProjectConfig config,
                                        OutputFormatter formatter) {
        List<ClassInfo> allClasses;
        if (indexed != null) {
            allClasses = indexed.getAllClasses();
        } else {
            CodeParser parser = new HybridJavaParser();
            allClasses = new ArrayList<>();
            for (Path file : javaFiles) allClasses.addAll(parser.parseClasses(file));
        }

        List<String> epAnnotations = config != null ? config.architecture().endpointAnnotations() : List.of();
        var mapper = new com.jsrc.app.architecture.EndpointMapper(epAnnotations);
        var endpoints = mapper.findEndpoints(allClasses);

        System.out.println(com.jsrc.app.output.JsonWriter.toJson(endpoints));
        return endpoints.size();
    }

    private static int runCheck(com.jsrc.app.index.IndexedCodebase indexed,
                                     List<Path> javaFiles, String rootPath,
                                     String ruleId,
                                     com.jsrc.app.config.ProjectConfig config,
                                     OutputFormatter formatter) {
        if (config == null || config.architecture().rules().isEmpty()) {
            System.err.println("Error: No architecture rules defined in .jsrc.yaml");
            System.exit(ExitCode.BAD_USAGE);
        }

        List<ClassInfo> allClasses;
        if (indexed != null) {
            allClasses = indexed.getAllClasses();
        } else {
            CodeParser parser = new HybridJavaParser();
            allClasses = new ArrayList<>();
            for (Path file : javaFiles) allClasses.addAll(parser.parseClasses(file));
        }

        var engine = new com.jsrc.app.architecture.RuleEngine(config.architecture());
        List<com.jsrc.app.architecture.Violation> violations;
        if (ruleId != null) {
            violations = engine.evaluateRule(ruleId, allClasses, javaFiles);
        } else {
            violations = engine.evaluate(allClasses, javaFiles);
        }

        formatter.printViolations(violations);
        return violations.size();
    }

    private static int runLayer(com.jsrc.app.index.IndexedCodebase indexed,
                                     List<Path> javaFiles, String rootPath,
                                     String layerName,
                                     com.jsrc.app.config.ProjectConfig config,
                                     OutputFormatter formatter) {
        if (config == null || config.architecture().layers().isEmpty()) {
            System.err.println("Error: No architecture layers defined in .jsrc.yaml");
            System.exit(ExitCode.BAD_USAGE);
        }

        List<ClassInfo> allClasses;
        if (indexed != null) {
            allClasses = indexed.getAllClasses();
        } else {
            CodeParser parser = new HybridJavaParser();
            allClasses = new ArrayList<>();
            for (Path file : javaFiles) allClasses.addAll(parser.parseClasses(file));
        }

        var resolver = new com.jsrc.app.architecture.LayerResolver(config.architecture().layers());
        List<ClassInfo> layerClasses = resolver.filterByLayer(allClasses, layerName);
        formatter.printClasses(layerClasses, Path.of(rootPath));
        return layerClasses.size();
    }

    private static int runChanged(String rootPath, OutputFormatter formatter) {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "diff", "--name-only", "HEAD");
            pb.directory(new java.io.File(rootPath));
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes()).trim();
            proc.waitFor();

            List<String> changedFiles = output.isEmpty() ? List.of()
                    : List.of(output.split("\n")).stream()
                            .filter(f -> f.endsWith(".java"))
                            .toList();

            Map<String, Object> result = new java.util.LinkedHashMap<>();
            result.put("changedFiles", changedFiles);
            result.put("totalChanged", changedFiles.size());
            System.out.println(com.jsrc.app.output.JsonWriter.toJson(result));
            return changedFiles.size();
        } catch (Exception e) {
            System.err.printf("Error running git diff: %s%n", e.getMessage());
            return 0;
        }
    }

    private static int runDiff(List<Path> javaFiles, String rootPath,
                                    OutputFormatter formatter) {
        Path root = Paths.get(rootPath);
        List<com.jsrc.app.index.IndexEntry> existing = com.jsrc.app.index.CodebaseIndex.load(root);
        if (existing.isEmpty()) {
            System.err.println("No index found. Run --index first.");
            return 0;
        }

        // Build lookup of indexed files
        Map<String, com.jsrc.app.index.IndexEntry> byPath = new java.util.HashMap<>();
        for (var entry : existing) {
            byPath.put(entry.path(), entry);
        }

        List<String> modified = new ArrayList<>();
        List<String> added = new ArrayList<>();
        java.util.Set<String> currentPaths = new java.util.HashSet<>();

        for (Path file : javaFiles) {
            String relativePath = root.relativize(file).toString();
            currentPaths.add(relativePath);
            var prev = byPath.get(relativePath);
            if (prev == null) {
                added.add(relativePath);
            } else {
                try {
                    // First check timestamp (fast), then hash if timestamp changed (accurate)
                    long currentModified = java.nio.file.Files.getLastModifiedTime(file).toMillis();
                    if (currentModified > prev.lastModified()) {
                        // Timestamp changed — verify with content hash to avoid false positives from touch
                        byte[] content = java.nio.file.Files.readAllBytes(file);
                        String hash = sha256(content);
                        if (!hash.equals(prev.contentHash())) {
                            modified.add(relativePath);
                        }
                    }
                } catch (java.io.IOException e) {
                    modified.add(relativePath);
                }
            }
        }

        List<String> deleted = byPath.keySet().stream()
                .filter(p -> !currentPaths.contains(p))
                .sorted()
                .toList();

        formatter.printDiff(modified, added, deleted);
        return modified.size() + added.size() + deleted.size();
    }

    private static int runCallers(List<Path> javaFiles, String methodName,
                                      OutputFormatter formatter) {
        CallGraphBuilder graphBuilder = new CallGraphBuilder();
        graphBuilder.build(javaFiles);

        var targets = graphBuilder.findMethodsByName(methodName);
        List<Map<String, Object>> callers = new ArrayList<>();
        for (var target : targets) {
            for (var call : graphBuilder.getCallersOf(target)) {
                Map<String, Object> ref = new java.util.LinkedHashMap<>();
                ref.put("className", call.caller().className());
                ref.put("methodName", call.caller().methodName());
                ref.put("line", call.line());
                ref.put("type", "direct");
                callers.add(ref);
            }
        }

        // Add reflective callers from invoker config
        var config = com.jsrc.app.config.ProjectConfig.load(java.nio.file.Path.of("."));
        if (config != null && !config.architecture().invokers().isEmpty()) {
            var resolver = new com.jsrc.app.architecture.InvokerResolver(config.architecture().invokers());
            for (var rc : resolver.resolve(javaFiles)) {
                if (rc.targetMethod().equals(methodName)) {
                    Map<String, Object> ref = new java.util.LinkedHashMap<>();
                    ref.put("className", rc.callerClass());
                    ref.put("methodName", rc.callerMethod());
                    ref.put("line", rc.line());
                    ref.put("type", "reflective");
                    ref.put("targetClass", rc.targetClass());
                    callers.add(ref);
                }
            }
        }

        formatter.printRefs(callers, "Callers", methodName);
        return callers.size();
    }

    private static int runCallees(List<Path> javaFiles, String methodName,
                                      OutputFormatter formatter) {
        CallGraphBuilder graphBuilder = new CallGraphBuilder();
        graphBuilder.build(javaFiles);

        var sources = graphBuilder.findMethodsByName(methodName);
        List<Map<String, Object>> callees = new ArrayList<>();
        for (var source : sources) {
            for (var call : graphBuilder.getCalleesOf(source)) {
                Map<String, Object> ref = new java.util.LinkedHashMap<>();
                ref.put("className", call.callee().className());
                ref.put("methodName", call.callee().methodName());
                ref.put("line", call.line());
                ref.put("type", "direct");
                callees.add(ref);
            }
        }

        formatter.printRefs(callees, "Callees", methodName);
        return callees.size();
    }

    private static int runRead(CodeParser parser, List<Path> javaFiles,
                                   String target, OutputFormatter formatter) {
        var reader = new com.jsrc.app.parser.SourceReader(parser);
        com.jsrc.app.parser.SourceReader.ReadResult result;

        if (target.contains(".")) {
            // Class.method format
            int dot = target.lastIndexOf('.');
            String className = target.substring(0, dot);
            String methodName = target.substring(dot + 1);
            result = reader.readMethod(javaFiles, className, methodName);
        } else {
            // Class only
            result = reader.readClass(javaFiles, target);
        }

        if (result != null) {
            formatter.printReadResult(result);
            return 1;
        }
        System.err.printf("'%s' not found.%n", target);
        return 0;
    }

    private static List<ClassInfo> getAllClasses(com.jsrc.app.index.IndexedCodebase indexed,
                                                    List<Path> javaFiles) {
        if (indexed != null) return indexed.getAllClasses();
        CodeParser parser = new HybridJavaParser();
        List<ClassInfo> all = new ArrayList<>();
        for (Path file : javaFiles) all.addAll(parser.parseClasses(file));
        return all;
    }

    /**
     * Resolves a class name, handling ambiguity. Returns the ClassInfo or exits.
     */
    private static ClassInfo resolveClass(List<ClassInfo> allClasses, String className) {
        var resolution = com.jsrc.app.util.ClassResolver.resolve(allClasses, className);
        return switch (resolution) {
            case com.jsrc.app.util.ClassResolver.Resolution.Found found -> found.classInfo();
            case com.jsrc.app.util.ClassResolver.Resolution.Ambiguous ambiguous -> {
                com.jsrc.app.util.ClassResolver.printAmbiguous(ambiguous.candidates(), className);
                System.exit(ExitCode.BAD_USAGE);
                yield null;
            }
            case com.jsrc.app.util.ClassResolver.Resolution.NotFound notFound -> {
                System.err.printf("Class '%s' not found.%n", className);
                yield null;
            }
        };
    }

    private static String sha256(byte[] data) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private static String validateArg(String value, String label) {
        String error = com.jsrc.app.util.InputValidator.validateIdentifier(value, label);
        if (error != null) {
            System.err.println("Error: " + error);
            System.exit(ExitCode.BAD_USAGE);
        }
        return value;
    }

    private static String resolveRoot(com.jsrc.app.config.ProjectConfig config) {
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
                        // Simple glob: ** matches any path segment
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
        System.err.println("Usage:");
        System.err.println("  jsrc <source-root> <method-name> [--json]                    Search for methods");
        System.err.println("  jsrc <source-root> --index                                  Build/update persistent index");
        System.err.println("  jsrc <source-root> --overview [--json]                       Codebase overview (files, classes, packages)");
        System.err.println("  jsrc <source-root> --deps <class> [--json]                   Class dependencies (imports, fields, ctor params)");
        System.err.println("  jsrc <source-root> --implements <iface> [--json]             Find implementors of an interface");
        System.err.println("  jsrc <source-root> --hierarchy <class> [--json]              Class hierarchy (extends/implements/subclasses)");
        System.err.println("  jsrc <source-root> --summary <class> [--json]                Class summary (signatures only)");
        System.err.println("  jsrc <source-root> --annotations <name> [--json]             Find annotated elements");
        System.err.println("  jsrc <source-root> --classes [--json]                        List all classes");
        System.err.println("  jsrc <source-root> --smells [--json]                         Detect code smells");
        System.err.println("  jsrc <source-root> --call-chain <method> [outdir] [--json]   Generate call chain diagrams");
    }

    private static void runIndex(CodeParser parser, List<Path> javaFiles, String rootPath) {
        Path root = Paths.get(rootPath);
        System.err.printf("Indexing %d Java files under '%s'...%n", javaFiles.size(), rootPath);

        var existing = com.jsrc.app.index.CodebaseIndex.load(root);
        var index = new com.jsrc.app.index.CodebaseIndex();
        int reindexed = index.build(parser, javaFiles, root, existing);

        try {
            index.save(root);
            System.err.printf("Done. Indexed %d files (%d re-indexed, %d cached).%n",
                    javaFiles.size(), reindexed, javaFiles.size() - reindexed);
        } catch (IOException ex) {
            System.err.printf("Error saving index: %s%n", ex.getMessage());
            System.exit(ExitCode.IO_ERROR);
        }
    }

    private static int runOverview(com.jsrc.app.index.IndexedCodebase indexed,
                                       List<Path> javaFiles, String rootPath,
                                       OutputFormatter formatter) {
        List<ClassInfo> allClasses;
        int fileCount;

        if (indexed != null) {
            allClasses = indexed.getAllClasses();
            fileCount = indexed.fileCount();
        } else {
            CodeParser parser = new HybridJavaParser();
            allClasses = new ArrayList<>();
            for (Path file : javaFiles) {
                allClasses.addAll(parser.parseClasses(file));
            }
            fileCount = javaFiles.size();
        }

        int totalClasses = 0, totalInterfaces = 0, totalMethods = 0;
        java.util.Set<String> packages = new java.util.TreeSet<>();
        for (ClassInfo ci : allClasses) {
            if (ci.isInterface()) totalInterfaces++;
            else totalClasses++;
            totalMethods += ci.methods().size();
            if (!ci.packageName().isEmpty()) packages.add(ci.packageName());
        }

        formatter.printOverview(new OverviewResult(
                fileCount, totalClasses, totalInterfaces,
                totalMethods, List.copyOf(packages)));
        return totalClasses + totalInterfaces;
    }

    private static int runDependencyAnalysis(List<Path> javaFiles,
                                                 String className, OutputFormatter formatter) {
        var analyzer = new com.jsrc.app.parser.DependencyAnalyzer();
        DependencyResult result = analyzer.analyze(javaFiles, className);
        if (result != null) {
            formatter.printDependencies(result);
            return 1;
        }
        System.err.printf("Class '%s' not found.%n", className);
        return 0;
    }

    private static int runImplements(com.jsrc.app.index.IndexedCodebase indexed,
                                        List<Path> javaFiles, String ifaceName,
                                        OutputFormatter formatter) {
        List<ClassInfo> allClasses;
        if (indexed != null) {
            allClasses = indexed.getAllClasses();
        } else {
            CodeParser parser = new HybridJavaParser();
            allClasses = new ArrayList<>();
            for (Path file : javaFiles) {
                allClasses.addAll(parser.parseClasses(file));
            }
        }

        List<Map<String, Object>> implementors = new ArrayList<>();
        for (ClassInfo ci : allClasses) {
            if (ci.interfaces().contains(ifaceName)) {
                Map<String, Object> entry = new java.util.LinkedHashMap<>();
                entry.put("name", ci.qualifiedName());
                entry.put("isAbstract", ci.isAbstract());
                implementors.add(entry);
            }
        }

        // Reuse printClasses for text, but for JSON emit focused list
        HierarchyResult result = new HierarchyResult(
                ifaceName, "", List.of(), List.of(),
                implementors.stream().map(m -> (String) m.get("name")).toList());
        formatter.printHierarchy(result);
        return result.implementors().size();
    }

    private static int runHierarchy(com.jsrc.app.index.IndexedCodebase indexed,
                                       List<Path> javaFiles, String className,
                                       OutputFormatter formatter) {
        List<ClassInfo> allClasses = getAllClasses(indexed, javaFiles);
        ClassInfo target = resolveClass(allClasses, className);
        if (target == null) return 0;

        // Find subclasses (classes that extend target)
        List<String> subClasses = allClasses.stream()
                .filter(ci -> ci.superClass().equals(target.name())
                        || ci.superClass().equals(target.qualifiedName()))
                .map(ClassInfo::qualifiedName)
                .toList();

        // Find implementors (if target is an interface)
        List<String> implementors = List.of();
        if (target.isInterface()) {
            implementors = allClasses.stream()
                    .filter(ci -> ci.interfaces().contains(target.name())
                            || ci.interfaces().contains(target.qualifiedName()))
                    .map(ClassInfo::qualifiedName)
                    .toList();
        }

        HierarchyResult result = new HierarchyResult(
                target.qualifiedName(), target.superClass(),
                target.interfaces(), subClasses, implementors);
        formatter.printHierarchy(result);
        return 1;
    }

    private static int runAnnotationSearch(com.jsrc.app.index.IndexedCodebase indexed,
                                              List<Path> javaFiles, String rootPath,
                                              String annotationName, OutputFormatter formatter) {
        List<AnnotationMatch> matches = new ArrayList<>();

        if (indexed != null) {
            // From index: methods
            for (MethodInfo m : indexed.findMethodsByAnnotation(annotationName)) {
                AnnotationInfo ann = m.annotations().stream()
                        .filter(a -> a.name().equals(annotationName))
                        .findFirst().orElse(AnnotationInfo.marker(annotationName));
                String filePath = indexed.findFileForClass(m.className());
                matches.add(new AnnotationMatch("method", m.name(), m.className(),
                        Path.of(filePath != null ? filePath : ""), m.startLine(), ann));
            }
            // From index: classes
            for (ClassInfo ci : indexed.findClassesByAnnotation(annotationName)) {
                AnnotationInfo ann = ci.annotations().stream()
                        .filter(a -> a.name().equals(annotationName))
                        .findFirst().orElse(AnnotationInfo.marker(annotationName));
                String filePath = indexed.findFileForClass(ci.name());
                matches.add(new AnnotationMatch("class", ci.name(), ci.name(),
                        Path.of(filePath != null ? filePath : ""), ci.startLine(), ann));
            }
        } else {
            CodeParser parser = new HybridJavaParser();
            for (Path file : javaFiles) {
                List<MethodInfo> methods = parser.findMethodsByAnnotation(file, annotationName);
                for (MethodInfo m : methods) {
                    AnnotationInfo ann = m.annotations().stream()
                            .filter(a -> a.name().equals(annotationName))
                            .findFirst().orElse(AnnotationInfo.marker(annotationName));
                    matches.add(new AnnotationMatch("method", m.name(), m.className(), file, m.startLine(), ann));
                }
                List<ClassInfo> classes = parser.parseClasses(file);
                for (ClassInfo ci : classes) {
                    ci.annotations().stream()
                            .filter(a -> a.name().equals(annotationName))
                            .findFirst()
                            .ifPresent(ann -> matches.add(
                                    new AnnotationMatch("class", ci.name(), ci.name(), file, ci.startLine(), ann)));
                }
            }
        }

        formatter.printAnnotationMatches(matches);
        return matches.size();
    }

    private static int runClassSummary(com.jsrc.app.index.IndexedCodebase indexed,
                                          List<Path> javaFiles, String rootPath,
                                          String className, OutputFormatter formatter) {
        List<ClassInfo> allClasses = getAllClasses(indexed, javaFiles);
        ClassInfo ci = resolveClass(allClasses, className);
        if (ci == null) return 0;

        String filePath = indexed != null ? indexed.findFileForClass(ci.name()) : null;
        formatter.printClassSummary(ci, Path.of(filePath != null ? filePath : ""));
        return 1;
    }

    private static int runClassListing(com.jsrc.app.index.IndexedCodebase indexed,
                                          List<Path> javaFiles, String rootPath,
                                          OutputFormatter formatter) {
        List<ClassInfo> allClasses;
        if (indexed != null) {
            allClasses = indexed.getAllClasses();
        } else {
            CodeParser parser = new HybridJavaParser();
            allClasses = new ArrayList<>();
            for (Path file : javaFiles) {
                allClasses.addAll(parser.parseClasses(file));
            }
        }

        formatter.printClasses(allClasses, Path.of(rootPath));
        return allClasses.size();
    }

    private static int runSmellDetection(CodeParser parser, List<Path> javaFiles,
                                           String rootPath, OutputFormatter formatter) {
        System.err.printf("Analyzing %d Java files under '%s' for code smells...%n",
                javaFiles.size(), rootPath);

        int totalSmells = 0;
        for (Path file : javaFiles) {
            List<CodeSmell> smells = parser.detectSmells(file);
            totalSmells += smells.size();
            formatter.printSmells(smells, file);
        }
        return totalSmells;
    }

    private static int runMethodSearch(CodeParser parser, List<Path> javaFiles,
                                         String rootPath, String methodName,
                                         OutputFormatter formatter) {
        System.err.printf("Scanning %d Java files under '%s' for method '%s'...%n",
                javaFiles.size(), rootPath, methodName);

        int totalFound = 0;
        for (Path file : javaFiles) {
            List<MethodInfo> methods = parser.findMethods(file, methodName);
            if (!methods.isEmpty()) {
                totalFound += methods.size();
                formatter.printMethods(methods, file, methodName);
            }
        }
        return totalFound;
    }

    private static int runCallChainAnalysis(List<Path> javaFiles, String rootPath,
                                              String methodName, String outputDir,
                                              OutputFormatter formatter) {
        System.err.printf("Building call graph for %d Java files under '%s'...%n",
                javaFiles.size(), rootPath);

        CallGraphBuilder graphBuilder = new CallGraphBuilder();
        graphBuilder.build(javaFiles);

        CallChainTracer tracer = new CallChainTracer(graphBuilder);
        List<CallChain> chains = tracer.traceToRoots(methodName);

        formatter.printCallChains(chains, methodName);

        if (!chains.isEmpty()) {
            MermaidDiagramGenerator generator = new MermaidDiagramGenerator();
            try {
                List<Path> files = generator.writeAll(chains, Paths.get(outputDir), methodName);
                for (Path file : files) {
                    System.err.printf("  Written: %s%n", file);
                }
            } catch (IOException ex) {
                System.err.printf("Error writing diagrams: %s%n", ex.getMessage());
                System.exit(ExitCode.IO_ERROR);
            }
        }
        return chains.size();
    }
}
