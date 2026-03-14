package com.jsrc.app;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

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
        OutputFormatter formatter = OutputFormatter.create(jsonOutput, signatureOnly);

        if (argList.size() < 2) {
            printUsage();
            System.exit(1);
        }

        String rootPath = argList.get(0);
        String command = argList.get(1);

        CodeBase project = new JavaCodeBase(rootPath, new CodeBaseLoader());
        List<Path> javaFiles = project.getFiles();

        if ("--summary".equals(command)) {
            if (argList.size() < 3) {
                System.err.println("Error: --summary requires a class name");
                printUsage();
                System.exit(1);
            }
            String className = argList.get(2);
            CodeParser parser = new HybridJavaParser();
            runClassSummary(parser, javaFiles, rootPath, className, formatter);
        } else if ("--annotations".equals(command)) {
            if (argList.size() < 3) {
                System.err.println("Error: --annotations requires an annotation name");
                printUsage();
                System.exit(1);
            }
            String annotationName = argList.get(2);
            CodeParser parser = new HybridJavaParser();
            runAnnotationSearch(parser, javaFiles, rootPath, annotationName, formatter);
        } else if ("--classes".equals(command)) {
            CodeParser parser = new HybridJavaParser();
            runClassListing(parser, javaFiles, rootPath, formatter);
        } else if ("--smells".equals(command)) {
            CodeParser parser = new HybridJavaParser();
            runSmellDetection(parser, javaFiles, rootPath, formatter);
        } else if ("--call-chain".equals(command)) {
            if (argList.size() < 3) {
                System.err.println("Error: --call-chain requires a method name");
                printUsage();
                System.exit(1);
            }
            String methodName = argList.get(2);
            String outputDir = argList.size() >= 4 ? argList.get(3) : "./call-chains";
            runCallChainAnalysis(javaFiles, rootPath, methodName, outputDir, formatter);
        } else {
            CodeParser parser = new HybridJavaParser();
            runMethodSearch(parser, javaFiles, rootPath, command, formatter);
        }
    }

    private static void printUsage() {
        System.err.println("Usage:");
        System.err.println("  jsrc <source-root> <method-name> [--json]                    Search for methods");
        System.err.println("  jsrc <source-root> --summary <class> [--json]                Class summary (signatures only)");
        System.err.println("  jsrc <source-root> --annotations <name> [--json]             Find annotated elements");
        System.err.println("  jsrc <source-root> --classes [--json]                        List all classes");
        System.err.println("  jsrc <source-root> --smells [--json]                         Detect code smells");
        System.err.println("  jsrc <source-root> --call-chain <method> [outdir] [--json]   Generate call chain diagrams");
    }

    private static void runAnnotationSearch(CodeParser parser, List<Path> javaFiles,
                                              String rootPath, String annotationName,
                                              OutputFormatter formatter) {
        System.err.printf("Searching for @%s in %d files under '%s'...%n",
                annotationName, javaFiles.size(), rootPath);

        List<AnnotationMatch> matches = new ArrayList<>();
        for (Path file : javaFiles) {
            // Search methods
            List<MethodInfo> methods = parser.findMethodsByAnnotation(file, annotationName);
            for (MethodInfo m : methods) {
                AnnotationInfo ann = m.annotations().stream()
                        .filter(a -> a.name().equals(annotationName))
                        .findFirst().orElse(AnnotationInfo.marker(annotationName));
                matches.add(new AnnotationMatch("method", m.name(), m.className(), file, m.startLine(), ann));
            }

            // Search classes
            List<ClassInfo> classes = parser.parseClasses(file);
            for (ClassInfo ci : classes) {
                ci.annotations().stream()
                        .filter(a -> a.name().equals(annotationName))
                        .findFirst()
                        .ifPresent(ann -> matches.add(
                                new AnnotationMatch("class", ci.name(), ci.name(), file, ci.startLine(), ann)));
            }
        }

        formatter.printAnnotationMatches(matches);
        System.err.printf("Found %d match(es).%n", matches.size());
    }

    private static void runClassSummary(CodeParser parser, List<Path> javaFiles,
                                          String rootPath, String className,
                                          OutputFormatter formatter) {
        for (Path file : javaFiles) {
            List<ClassInfo> classes = parser.parseClasses(file);
            for (ClassInfo ci : classes) {
                if (ci.name().equals(className) || ci.qualifiedName().equals(className)) {
                    formatter.printClassSummary(ci, file);
                    return;
                }
            }
        }
        System.err.printf("Class '%s' not found.%n", className);
    }

    private static void runClassListing(CodeParser parser, List<Path> javaFiles,
                                          String rootPath, OutputFormatter formatter) {
        System.err.printf("Scanning %d Java files under '%s' for classes...%n",
                javaFiles.size(), rootPath);

        List<ClassInfo> allClasses = new ArrayList<>();
        for (Path file : javaFiles) {
            allClasses.addAll(parser.parseClasses(file));
        }

        formatter.printClasses(allClasses, Path.of(rootPath));
        System.err.printf("Found %d type(s).%n", allClasses.size());
    }

    private static void runSmellDetection(CodeParser parser, List<Path> javaFiles,
                                           String rootPath, OutputFormatter formatter) {
        System.err.printf("Analyzing %d Java files under '%s' for code smells...%n",
                javaFiles.size(), rootPath);

        for (Path file : javaFiles) {
            List<CodeSmell> smells = parser.detectSmells(file);
            formatter.printSmells(smells, file);
        }
    }

    private static void runMethodSearch(CodeParser parser, List<Path> javaFiles,
                                         String rootPath, String methodName,
                                         OutputFormatter formatter) {
        System.err.printf("Scanning %d Java files under '%s' for method '%s'...%n",
                javaFiles.size(), rootPath, methodName);

        for (Path file : javaFiles) {
            List<MethodInfo> methods = parser.findMethods(file, methodName);
            if (!methods.isEmpty()) {
                formatter.printMethods(methods, file, methodName);
            }
        }
    }

    private static void runCallChainAnalysis(List<Path> javaFiles, String rootPath,
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
                System.exit(1);
            }
        }
    }
}
