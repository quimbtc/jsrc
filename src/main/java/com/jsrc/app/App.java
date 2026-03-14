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
import com.jsrc.app.output.DependencyResult.FieldDep;
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
        boolean signatureOnly = argList.remove("--signature-only");
        boolean showMetrics = argList.remove("--metrics");

        // Extract --config <path> if present
        String configPath = null;
        int configIdx = argList.indexOf("--config");
        if (configIdx >= 0 && configIdx + 1 < argList.size()) {
            configPath = argList.get(configIdx + 1);
            argList.remove(configIdx + 1);
            argList.remove(configIdx);
        }

        OutputFormatter formatter = OutputFormatter.create(jsonOutput, signatureOnly);

        // Try loading project config
        com.jsrc.app.config.ProjectConfig config = configPath != null
                ? com.jsrc.app.config.ProjectConfig.loadFrom(Path.of(configPath))
                : com.jsrc.app.config.ProjectConfig.load(Path.of("."));

        // Resolve source root: explicit arg > config sourceRoots > pwd
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

        // Try loading index for full-parse commands (auto-refreshes stale entries)
        var indexedCodebase = com.jsrc.app.index.IndexedCodebase.tryLoad(Paths.get(rootPath), javaFiles);

        var timer = com.jsrc.app.util.StopWatch.start();
        int[] resultCount = {0}; // mutable counter for lambdas

        if ("--index".equals(command)) {
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
        var javaParser = new com.github.javaparser.JavaParser();
        for (Path file : javaFiles) {
            try {
                String source = java.nio.file.Files.readString(file);
                var parseResult = javaParser.parse(source);
                if (!parseResult.isSuccessful() || parseResult.getResult().isEmpty()) continue;
                var cu = parseResult.getResult().get();

                for (var cid : cu.findAll(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class)) {
                    if (!cid.getNameAsString().equals(className)) continue;

                    List<String> imports = cu.getImports().stream()
                            .map(imp -> imp.getNameAsString())
                            .toList();

                    List<FieldDep> fieldDeps = cid.getFields().stream()
                            .flatMap(f -> f.getVariables().stream()
                                    .map(v -> new FieldDep(f.getCommonType().asString(), v.getNameAsString())))
                            .toList();

                    List<FieldDep> ctorDeps = cid.getConstructors().stream()
                            .flatMap(c -> c.getParameters().stream()
                                    .map(p -> new FieldDep(p.getTypeAsString(), p.getNameAsString())))
                            .toList();

                    String qualifiedName = cu.getPackageDeclaration()
                            .map(pd -> pd.getNameAsString() + "." + className)
                            .orElse(className);

                    formatter.printDependencies(new DependencyResult(qualifiedName, imports, fieldDeps, ctorDeps));
                    return 1;
                }
            } catch (Exception e) {
                // skip unparseable files
            }
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

        // Find target class
        ClassInfo target = allClasses.stream()
                .filter(ci -> ci.name().equals(className) || ci.qualifiedName().equals(className))
                .findFirst().orElse(null);

        if (target == null) {
            System.err.printf("Class '%s' not found.%n", className);
            return 0;
        }

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
        if (indexed != null) {
            for (ClassInfo ci : indexed.getAllClasses()) {
                if (ci.name().equals(className) || ci.qualifiedName().equals(className)) {
                    String filePath = indexed.findFileForClass(className);
                    formatter.printClassSummary(ci, Path.of(filePath != null ? filePath : ""));
                    return 1;
                }
            }
        } else {
            CodeParser parser = new HybridJavaParser();
            for (Path file : javaFiles) {
                List<ClassInfo> classes = parser.parseClasses(file);
                for (ClassInfo ci : classes) {
                    if (ci.name().equals(className) || ci.qualifiedName().equals(className)) {
                        formatter.printClassSummary(ci, file);
                        return 1;
                    }
                }
            }
        }
        System.err.printf("Class '%s' not found.%n", className);
        return 0;
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
