package com.jsrc.app.architecture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.jsrc.app.config.ArchitectureConfig.InvokerDef;
import com.jsrc.app.parser.model.MethodCall;
import com.jsrc.app.parser.model.MethodReference;

/**
 * Resolves reflective method invocations where the target method name
 * is passed as a string argument. E.g.:
 * <pre>
 * ejecutarMetodo("calcularImporte", params)
 * → resolves to AdaptadorBean.calcularImporte()
 * </pre>
 */
public class InvokerResolver {

    private static final Logger logger = LoggerFactory.getLogger(InvokerResolver.class);

    private final List<InvokerDef> invokers;
    private final Map<String, String> invokerMethods; // method name → resolveClass convention

    public InvokerResolver(List<InvokerDef> invokers) {
        this.invokers = invokers;
        this.invokerMethods = new LinkedHashMap<>();
        for (InvokerDef inv : invokers) {
            invokerMethods.put(inv.method(), inv.resolveClass());
        }
    }

    /**
     * A resolved reflective call.
     */
    public record ReflectiveCall(
            String callerClass,
            String callerMethod,
            String targetClass,
            String targetMethod,
            int line
    ) {}

    /**
     * Scans files for reflective invocations and resolves them.
     */
    public List<ReflectiveCall> resolve(List<Path> files) {
        List<ReflectiveCall> results = new ArrayList<>();
        if (invokers.isEmpty()) return results;

        var javaParser = new JavaParser();
        for (Path file : files) {
            try {
                String source = Files.readString(file);
                var parseResult = javaParser.parse(source);
                if (!parseResult.isSuccessful() || parseResult.getResult().isEmpty()) continue;
                CompilationUnit cu = parseResult.getResult().get();

                for (ClassOrInterfaceDeclaration cid : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                    String callerClass = cid.getNameAsString();
                    for (MethodDeclaration md : cid.getMethods()) {
                        for (MethodCallExpr call : md.findAll(MethodCallExpr.class)) {
                            String methodName = call.getNameAsString();
                            if (!invokerMethods.containsKey(methodName)) continue;

                            InvokerDef inv = invokers.stream()
                                    .filter(i -> i.method().equals(methodName))
                                    .findFirst().orElse(null);
                            if (inv == null) continue;

                            // Extract string literal from target arg
                            if (call.getArguments().size() <= inv.targetArg()) continue;
                            var arg = call.getArguments().get(inv.targetArg());
                            if (!(arg instanceof StringLiteralExpr strLit)) continue;

                            String targetMethod = strLit.getValue();
                            String targetClass = resolveTargetClass(callerClass, inv);
                            int line = call.getBegin().map(p -> p.line).orElse(0);

                            results.add(new ReflectiveCall(
                                    callerClass, md.getNameAsString(),
                                    targetClass, targetMethod, line));
                        }
                    }
                }
            } catch (IOException e) {
                logger.debug("Error reading {}: {}", file, e.getMessage());
            }
        }

        logger.info("Resolved {} reflective invocation(s)", results.size());
        return results;
    }

    /**
     * Converts reflective calls to MethodCall edges for the call graph.
     */
    public List<MethodCall> toCallEdges(List<ReflectiveCall> calls) {
        return calls.stream()
                .map(rc -> new MethodCall(
                        new MethodReference(rc.callerClass(), rc.callerMethod(), -1, null),
                        new MethodReference(rc.targetClass(), rc.targetMethod(), -1, null),
                        rc.line()))
                .toList();
    }

    /**
     * Resolves target class name by convention.
     * E.g. caller "LiquidacionDetalle" + convention "adaptadorBean"
     * → target "LiquidacionAdaptadorBean"
     */
    private String resolveTargetClass(String callerClass, InvokerDef inv) {
        String resolveClass = inv.resolveClass();
        if (resolveClass == null || resolveClass.isEmpty()) return "?";

        // Extract prefix: remove configurable suffixes
        String prefix = callerClass;
        for (String suffix : inv.callerSuffixes()) {
            if (prefix.endsWith(suffix)) {
                prefix = prefix.substring(0, prefix.length() - suffix.length());
                break;
            }
        }

        // Capitalize convention name and append
        String capitalizedConvention = resolveClass.substring(0, 1).toUpperCase()
                + resolveClass.substring(1);
        return prefix + capitalizedConvention;
    }
}
