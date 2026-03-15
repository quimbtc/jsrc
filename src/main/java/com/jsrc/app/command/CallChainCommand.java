package com.jsrc.app.command;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

import com.jsrc.app.analysis.CallChainTracer;
import com.jsrc.app.analysis.CallGraphBuilder;
import com.jsrc.app.analysis.MermaidDiagramGenerator;
import com.jsrc.app.architecture.InvokerResolver;
import com.jsrc.app.parser.model.MethodCall;

public class CallChainCommand implements Command {
    private final String methodName;
    private final String outputDir;

    public CallChainCommand(String methodName, String outputDir) {
        this.methodName = methodName;
        this.outputDir = outputDir;
    }

    @Override
    public int execute(CommandContext ctx) {
        CallGraphBuilder graphBuilder = new CallGraphBuilder();
        if (ctx.indexed() != null && ctx.indexed().hasCallEdges()) {
            graphBuilder.loadFromIndex(ctx.indexed().getEntries());
        } else {
            graphBuilder.build(ctx.javaFiles());
        }

        // Always add reflective call edges from invoker config.
        // Even if index has edges, reflective ones may be missing
        // (e.g. index built before yaml was configured correctly).
        if (ctx.config() != null && !ctx.config().architecture().invokers().isEmpty()) {
            var resolver = new InvokerResolver(ctx.config().architecture().invokers());
            var reflective = resolver.resolve(ctx.javaFiles());
            for (MethodCall edge : resolver.toCallEdges(reflective)) {
                graphBuilder.addEdge(edge);
            }
        }

        // Create tracer with stop methods from config (e.g. event handlers)
        java.util.Set<String> stopMethods = (ctx.config() != null && !ctx.config().architecture().chainStopMethods().isEmpty())
                ? new java.util.HashSet<>(ctx.config().architecture().chainStopMethods())
                : java.util.Set.of();
        CallChainTracer tracer = new CallChainTracer(graphBuilder, 20, stopMethods);
        var chains = tracer.traceToRoots(methodName);

        ctx.formatter().printCallChains(chains, methodName);

        if (!chains.isEmpty()) {
            MermaidDiagramGenerator generator = new MermaidDiagramGenerator();
            try {
                var files = generator.writeAll(chains, Paths.get(outputDir), methodName);
                for (Path file : files) {
                    System.err.printf("  Written: %s%n", file);
                }
            } catch (IOException ex) {
                System.err.printf("Error writing diagrams: %s%n", ex.getMessage());
            }
        }
        return chains.size();
    }
}
