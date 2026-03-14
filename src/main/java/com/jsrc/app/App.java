package com.jsrc.app;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import com.jsrc.app.codebase.CodeBase;
import com.jsrc.app.codebase.CodeBaseLoader;
import com.jsrc.app.codebase.JavaCodeBase;
import com.jsrc.app.parser.CallChainTracer;
import com.jsrc.app.parser.CallGraphBuilder;
import com.jsrc.app.parser.CodeParser;
import com.jsrc.app.parser.HybridJavaParser;
import com.jsrc.app.parser.MermaidDiagramGenerator;
import com.jsrc.app.parser.model.CallChain;
import com.jsrc.app.parser.model.CodeSmell;
import com.jsrc.app.parser.model.MethodInfo;

public class App {

    public static void main(String[] args) {
        if (args.length < 2) {
            printUsage();
            System.exit(1);
        }

        String rootPath = args[0];
        String command = args[1];

        CodeBase project = new JavaCodeBase(rootPath, new CodeBaseLoader());
        List<Path> javaFiles = project.getFiles();

        if ("--smells".equals(command)) {
            CodeParser parser = new HybridJavaParser();
            runSmellDetection(parser, javaFiles, rootPath);
        } else if ("--call-chain".equals(command)) {
            if (args.length < 3) {
                System.err.println("Error: --call-chain requires a method name");
                printUsage();
                System.exit(1);
            }
            String methodName = args[2];
            String outputDir = args.length >= 4 ? args[3] : "./call-chains";
            runCallChainAnalysis(javaFiles, rootPath, methodName, outputDir);
        } else {
            CodeParser parser = new HybridJavaParser();
            runMethodSearch(parser, javaFiles, rootPath, command);
        }
    }

    private static void printUsage() {
        System.err.println("Usage:");
        System.err.println("  jsrc <source-root> <method-name>                    Search for methods");
        System.err.println("  jsrc <source-root> --smells                         Detect code smells");
        System.err.println("  jsrc <source-root> --call-chain <method> [outdir]   Generate call chain diagrams");
    }

    private static void runSmellDetection(CodeParser parser, List<Path> javaFiles, String rootPath) {
        System.out.printf("Analyzing %d Java files under '%s' for code smells...%n",
                javaFiles.size(), rootPath);

        int totalSmells = 0;
        int errors = 0;
        int warnings = 0;
        int infos = 0;

        for (Path file : javaFiles) {
            List<CodeSmell> smells = parser.detectSmells(file);
            if (smells.isEmpty()) continue;

            System.out.printf("%n--- %s ---%n", file);
            for (CodeSmell smell : smells) {
                totalSmells++;
                switch (smell.severity()) {
                    case ERROR -> errors++;
                    case WARNING -> warnings++;
                    case INFO -> infos++;
                }
                System.out.printf("  [%s] %s at line %d in %s%n    %s%n",
                        smell.severity(), smell.ruleId(), smell.line(),
                        smell.methodName().isEmpty() ? smell.className() : smell.methodName() + "()",
                        smell.message());
            }
        }

        System.out.printf("%nDone. Found %d smell(s): %d error(s), %d warning(s), %d info(s).%n",
                totalSmells, errors, warnings, infos);
    }

    private static void runMethodSearch(CodeParser parser, List<Path> javaFiles,
                                         String rootPath, String methodName) {
        System.out.printf("Scanning %d Java files under '%s' for method '%s'...%n",
                javaFiles.size(), rootPath, methodName);

        int totalFound = 0;
        for (Path file : javaFiles) {
            List<MethodInfo> methods = parser.findMethods(file, methodName);
            for (MethodInfo m : methods) {
                totalFound++;
                System.out.printf("%n[%s] %s:%d-%d%n",
                        m.className().isEmpty() ? file.getFileName() : m.className(),
                        file, m.startLine(), m.endLine());
                System.out.printf("  %s%n", m.signature());

                if (!m.annotations().isEmpty()) {
                    System.out.printf("  Annotations: %s%n", m.annotations());
                }
                if (!m.thrownExceptions().isEmpty()) {
                    System.out.printf("  Throws: %s%n", String.join(", ", m.thrownExceptions()));
                }
                if (!m.typeParameters().isEmpty()) {
                    System.out.printf("  Type params: %s%n", String.join(", ", m.typeParameters()));
                }
                if (m.javadoc() != null) {
                    String firstLine = m.javadoc().lines().findFirst().orElse("").trim();
                    if (firstLine.startsWith("*")) firstLine = firstLine.substring(1).trim();
                    System.out.printf("  Javadoc: %s%n", firstLine);
                }
            }
        }

        System.out.printf("%nDone. Found %d match(es).%n", totalFound);
    }

    private static void runCallChainAnalysis(List<Path> javaFiles, String rootPath,
                                              String methodName, String outputDir) {
        System.out.printf("Building call graph for %d Java files under '%s'...%n",
                javaFiles.size(), rootPath);

        CallGraphBuilder graphBuilder = new CallGraphBuilder();
        graphBuilder.build(javaFiles);

        System.out.printf("Tracing call chains to '%s'...%n", methodName);

        CallChainTracer tracer = new CallChainTracer(graphBuilder);
        List<CallChain> chains = tracer.traceToRoots(methodName);

        if (chains.isEmpty()) {
            System.out.printf("No call chains found for method '%s'.%n", methodName);
            return;
        }

        System.out.printf("Found %d call chain(s). Generating diagrams...%n", chains.size());

        MermaidDiagramGenerator generator = new MermaidDiagramGenerator();
        try {
            List<Path> files = generator.writeAll(chains, Paths.get(outputDir), methodName);
            for (Path file : files) {
                System.out.printf("  Written: %s%n", file);
            }
            System.out.printf("%nDone. %d diagram(s) saved to '%s'.%n", files.size(), outputDir);
        } catch (IOException ex) {
            System.err.printf("Error writing diagrams: %s%n", ex.getMessage());
            System.exit(1);
        }
    }
}
