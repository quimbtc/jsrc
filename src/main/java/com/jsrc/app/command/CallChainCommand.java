package com.jsrc.app.command;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.jsrc.app.analysis.CallChainTracer;
import com.jsrc.app.analysis.CallGraph;
import com.jsrc.app.analysis.CallGraphBuilder;
import com.jsrc.app.analysis.MermaidDiagramGenerator;
import com.jsrc.app.architecture.InvokerResolver;

import com.jsrc.app.parser.model.CallChain;
import com.jsrc.app.parser.model.MethodCall;
import com.jsrc.app.parser.model.MethodReference;
import com.jsrc.app.util.MethodResolver;
import com.jsrc.app.util.MethodTargetResolver;

public class CallChainCommand implements Command {
    private final String methodName;
    private final String outputDir;

    public CallChainCommand(String methodName, String outputDir) {
        this.methodName = methodName;
        this.outputDir = outputDir;
    }

    @Override
    public int execute(CommandContext ctx) {
        CallGraph graph = ctx.callGraph();

        // Create tracer with stop methods from config (e.g. event handlers)
        java.util.Set<String> stopMethods = (ctx.config() != null && !ctx.config().architecture().chainStopMethods().isEmpty())
                ? new java.util.HashSet<>(ctx.config().architecture().chainStopMethods())
                : java.util.Set.of();
        CallChainTracer tracer = new CallChainTracer(graph, 20, stopMethods);

        // Build signature map from index for enriched output and disambiguation
        Map<String, String> signatures = MethodTargetResolver.buildSignatureMap(ctx.indexed());

        // Resolve targets using centralized resolver
        var ref = MethodResolver.parse(methodName);
        var resolved = MethodTargetResolver.resolve(ref, graph);

        var packages = MethodTargetResolver.buildClassPackageMap(ctx.indexed());

        if (resolved.isAmbiguous()) {
            var candidates = MethodTargetResolver.buildCandidates(resolved.targets(), signatures, packages);
            Map<String, Object> result = new java.util.LinkedHashMap<>();
            result.put("ambiguous", true);
            result.put("methodName", ref.hasClassName()
                    ? ref.className() + "." + ref.methodName() : ref.methodName());
            result.put("candidates", candidates);
            result.put("message", "Multiple methods found. Use Class.method(Type1,Type2) to disambiguate.");
            ctx.formatter().printResult(result);
            return 0;
        }

        var targets = resolved.targets();

        List<CallChain> chains = new java.util.ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (MethodReference target : targets) {
            for (CallChain chain : tracer.traceToRoots(target)) {
                if (seen.add(chain.summary())) chains.add(chain);
            }
        }

        // Filter chains by arg count when specific overload requested
        if (ref.hasParamTypes()) {
            int expectedCount = ref.paramTypes().size();
            int beforeFilter = chains.size();
            chains = chains.stream().filter(chain -> {
                MethodCall lastStep = chain.steps().getLast();
                int calleeArgs = lastStep.callee().parameterCount();
                if (calleeArgs >= 0 && calleeArgs != expectedCount) {
                    return false;
                }
                return true;
            }).toList();
            if (chains.size() < beforeFilter) {
                System.err.printf("Filtered %d chain(s) by param count %d → %d remaining%n",
                        beforeFilter - chains.size(), expectedCount, chains.size());
            }
        }

        // Identify dead-end roots (methods with no callers — potential dead code)
        java.util.Set<String> deadEndRoots = new java.util.HashSet<>();
        for (CallChain chain : chains) {
            MethodReference root = chain.root();
            if (graph.isRoot(root)) {
                deadEndRoots.add(root.className() + "." + root.methodName());
            }
        }

        ctx.formatter().printCallChains(
                new com.jsrc.app.model.CallChainOutput(chains, methodName, signatures, deadEndRoots));

        if (!chains.isEmpty()) {
            MermaidDiagramGenerator generator = new MermaidDiagramGenerator(signatures, deadEndRoots);
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
