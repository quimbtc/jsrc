package com.jsrc.app.command;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.jsrc.app.analysis.CallChainTracer;
import com.jsrc.app.analysis.CallGraphBuilder;
import com.jsrc.app.analysis.MermaidDiagramGenerator;
import com.jsrc.app.architecture.InvokerResolver;
import com.jsrc.app.index.IndexedClass;
import com.jsrc.app.index.IndexedMethod;
import com.jsrc.app.index.IndexEntry;
import com.jsrc.app.parser.model.CallChain;
import com.jsrc.app.parser.model.MethodCall;
import com.jsrc.app.parser.model.MethodReference;
import com.jsrc.app.util.MethodResolver;

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

        // Add reflective call edges from invoker config.
        // Skip if index has call edges (they include reflective edges from --index).
        // Only resolve at runtime when there's no index or no call edges indexed.
        if (ctx.config() != null && !ctx.config().architecture().invokers().isEmpty()
                && !(ctx.indexed() != null && ctx.indexed().hasCallEdges())) {
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

        // Build signature map from index for enriched output and disambiguation
        Map<String, String> signatures = buildSignatureMap(ctx);

        // Parse input: supports methodName, Class.method, Class.method(Type1,Type2)
        var ref = MethodResolver.parse(methodName);
        java.util.Set<MethodReference> allTargets = graphBuilder.findMethodsByName(ref.methodName());

        // Filter by class if specified
        java.util.Set<MethodReference> targets;
        if (ref.hasClassName()) {
            targets = new java.util.HashSet<>();
            for (MethodReference target : allTargets) {
                if (target.className().equals(ref.className())) {
                    targets.add(target);
                }
            }
        } else {
            targets = allTargets;
        }

        // Check for ambiguity using signatures from index (more reliable than Set size)
        if (!ref.hasParamTypes()) {
            String lookupName = ref.hasClassName() ? ref.className() + "." + ref.methodName() : ref.methodName();
            java.util.Set<String> candidates = new java.util.TreeSet<>();
            // Scan all signature keys for matches
            for (var entry : signatures.entrySet()) {
                String key = entry.getKey();
                // Match "Class.method/N" or "Class.method"
                if (ref.hasClassName()) {
                    if (key.startsWith(ref.className() + "." + ref.methodName())) {
                        candidates.add(ref.className() + "." + ref.methodName() + entry.getValue());
                    }
                } else {
                    // Match any "*.methodName/N"
                    if (key.contains("." + ref.methodName() + "/") || key.endsWith("." + ref.methodName())) {
                        String className = key.substring(0, key.indexOf("." + ref.methodName()));
                        candidates.add(className + "." + ref.methodName() + entry.getValue());
                    }
                }
            }
            if (candidates.size() > 1) {
                Map<String, Object> result = new java.util.LinkedHashMap<>();
                result.put("ambiguous", true);
                result.put("methodName", lookupName);
                result.put("candidates", new java.util.ArrayList<>(candidates));
                result.put("message", "Multiple methods found. Use Class.method(Type1,Type2) to disambiguate.");
                ctx.formatter().printResult(result);
                return 0;
            }
        }

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
            chains = chains.stream().filter(chain -> {
                // Check the last step — the callee is the target method
                MethodCall lastStep = chain.steps().getLast();
                int calleeArgs = lastStep.callee().parameterCount();
                if (calleeArgs >= 0) return calleeArgs == expectedCount;
                return true; // can't verify, include
            }).toList();
        }

        ctx.formatter().printCallChains(chains, methodName, signatures);

        if (!chains.isEmpty()) {
            MermaidDiagramGenerator generator = new MermaidDiagramGenerator(signatures);
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

    /**
     * Builds a map of "ClassName.methodName" → parameter signature from the index.
     * Used to enrich call chain output with parameter types.
     */
    private static Map<String, String> buildSignatureMap(CommandContext ctx) {
        Map<String, String> map = new HashMap<>();
        if (ctx.indexed() == null) return map;
        for (IndexEntry entry : ctx.indexed().getEntries()) {
            for (IndexedClass ic : entry.classes()) {
                for (IndexedMethod im : ic.methods()) {
                    // Include param count in key to distinguish overloads
                    String params = (im.signature() != null && !im.signature().isEmpty())
                            ? extractParams(im.signature()) : "()";
                    int paramCount = countParams(params);
                    String key = ic.name() + "." + im.name() + "/" + paramCount;
                    map.putIfAbsent(key, params);
                    // Also store without param count as fallback
                    map.putIfAbsent(ic.name() + "." + im.name(), params);
                }
            }
        }
        return map;
    }

    private static int countParams(String params) {
        if (params == null || params.equals("()")) return 0;
        String inner = params.substring(1, params.length() - 1).trim();
        if (inner.isEmpty()) return 0;
        return inner.split(",").length;
    }

    private static String extractParams(String signature) {
        int open = signature.indexOf('(');
        int close = signature.lastIndexOf(')');
        if (open < 0 || close < 0 || close <= open) return "()";
        String inner = signature.substring(open + 1, close).trim();
        if (inner.isEmpty()) return "()";
        // Simplify: "Facturacion fto, boolean usarCache" → "(Facturacion, boolean)"
        String[] parts = inner.split(",");
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i].trim();
            // Take type only (first token), skip param name
            String[] tokens = part.split("\\s+");
            sb.append(tokens[0]);
            if (i < parts.length - 1) sb.append(", ");
        }
        sb.append(")");
        return sb.toString();
    }
}
