package com.jsrc.app.parser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.jsrc.app.parser.model.MethodCall;
import com.jsrc.app.parser.model.MethodReference;

/**
 * Builds a directed call graph across all Java files in a codebase.
 * <p>
 * For each method, discovers all {@link MethodCallExpr} nodes and resolves
 * the receiver type using local variables, parameters, fields, and class names.
 * Unresolvable calls fall back to name-only matching (className = "?").
 */
public class CallGraphBuilder {

    private static final Logger logger = LoggerFactory.getLogger(CallGraphBuilder.class);

    private final JavaParser javaParser;

    private final Map<MethodReference, Set<MethodCall>> callerIndex = new HashMap<>();
    private final Map<MethodReference, Set<MethodCall>> calleeIndex = new HashMap<>();
    private final Set<MethodReference> allMethods = new HashSet<>();
    private final Map<String, Set<MethodReference>> methodsByName = new HashMap<>();

    public CallGraphBuilder() {
        this.javaParser = new JavaParser();
    }

    /**
     * Parses all given files and builds the call graph.
     * Clears any previously built state before starting.
     */
    public void build(List<Path> javaFiles) {
        callerIndex.clear();
        calleeIndex.clear();
        allMethods.clear();
        methodsByName.clear();

        Map<Path, CompilationUnit> compilationUnits = new HashMap<>();
        Map<String, ClassContext> classContexts = new HashMap<>();

        for (Path file : javaFiles) {
            CompilationUnit cu = parseFile(file);
            if (cu == null) continue;
            compilationUnits.put(file, cu);
            registerClasses(cu, file, classContexts);
        }

        for (var entry : compilationUnits.entrySet()) {
            Path file = entry.getKey();
            CompilationUnit cu = entry.getValue();
            analyzeMethodCalls(cu, file, classContexts);
        }

        logger.info("Call graph built: {} methods, {} call edges",
                allMethods.size(), callerIndex.values().stream().mapToInt(Set::size).sum());
    }

    /**
     * Returns all calls where {@code method} is the callee (who calls this method?).
     */
    public Set<MethodCall> getCallersOf(MethodReference method) {
        return callerIndex.getOrDefault(method, Collections.emptySet());
    }

    /**
     * Returns all calls where {@code method} is the caller (what does this method call?).
     */
    public Set<MethodCall> getCalleesOf(MethodReference method) {
        return calleeIndex.getOrDefault(method, Collections.emptySet());
    }

    public Set<MethodReference> getAllMethods() {
        return Collections.unmodifiableSet(allMethods);
    }

    /**
     * Finds all registered methods matching the given name (across all classes).
     */
    public Set<MethodReference> findMethodsByName(String methodName) {
        return methodsByName.getOrDefault(methodName, Collections.emptySet());
    }

    /**
     * Returns all method references that appear as callees in the caller index.
     * Used for fuzzy matching across interface/implementation boundaries.
     */
    public Set<MethodReference> getAllCallerIndexKeys() {
        return Collections.unmodifiableSet(callerIndex.keySet());
    }

    /**
     * Returns true if no method in the graph calls this method.
     */
    public boolean isRoot(MethodReference method) {
        Set<MethodCall> callers = callerIndex.get(method);
        return callers == null || callers.isEmpty();
    }

    // -- registration pass --

    private void registerClasses(CompilationUnit cu, Path file,
                                 Map<String, ClassContext> classContexts) {
        for (ClassOrInterfaceDeclaration cid : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            String className = cid.getNameAsString();
            String qualifiedKey = buildQualifiedKey(cid);
            ClassContext ctx = new ClassContext(file);

            for (FieldDeclaration field : cid.getFields()) {
                String fieldType = field.getCommonType().asString();
                for (VariableDeclarator var : field.getVariables()) {
                    ctx.fieldTypes.put(var.getNameAsString(), fieldType);
                }
            }

            for (MethodDeclaration md : cid.getMethods()) {
                MethodReference ref = new MethodReference(
                        className, md.getNameAsString(),
                        md.getParameters().size(), file);
                allMethods.add(ref);
                methodsByName.computeIfAbsent(md.getNameAsString(), k -> new HashSet<>()).add(ref);
            }

            classContexts.put(qualifiedKey, ctx);
            classContexts.putIfAbsent(className, ctx);
        }
    }

    private String buildQualifiedKey(ClassOrInterfaceDeclaration cid) {
        StringBuilder sb = new StringBuilder(cid.getNameAsString());
        Node parent = cid.getParentNode().orElse(null);
        while (parent instanceof ClassOrInterfaceDeclaration outer) {
            sb.insert(0, outer.getNameAsString() + ".");
            parent = outer.getParentNode().orElse(null);
        }
        return sb.toString();
    }

    // -- call analysis pass --

    private void analyzeMethodCalls(CompilationUnit cu, Path file,
                                    Map<String, ClassContext> classContexts) {
        for (ClassOrInterfaceDeclaration cid : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            String className = cid.getNameAsString();
            String qualifiedKey = buildQualifiedKey(cid);
            ClassContext classCtx = classContexts.getOrDefault(qualifiedKey,
                    classContexts.getOrDefault(className, new ClassContext(file)));

            for (MethodDeclaration md : cid.getMethods()) {
                MethodReference caller = new MethodReference(
                        className, md.getNameAsString(),
                        md.getParameters().size(), file);

                Map<String, String> localTypes = buildLocalTypeMap(md);

                for (MethodCallExpr callExpr : md.findAll(MethodCallExpr.class)) {
                    MethodReference callee = resolveCallee(callExpr, className, localTypes, classCtx, classContexts);
                    int line = callExpr.getBegin().map(p -> p.line).orElse(-1);
                    MethodCall call = new MethodCall(caller, callee, line);

                    calleeIndex.computeIfAbsent(caller, k -> new HashSet<>()).add(call);
                    callerIndex.computeIfAbsent(callee, k -> new HashSet<>()).add(call);
                }
            }
        }
    }

    @SuppressWarnings("null")
    private MethodReference resolveCallee(MethodCallExpr callExpr, String currentClass,
                                          Map<String, String> localTypes,
                                          ClassContext classCtx,
                                          Map<String, ClassContext> allClasses) {
        String methodName = callExpr.getNameAsString();
        int argCount = callExpr.getArguments().size();

        if (callExpr.getScope().isEmpty()) {
            return new MethodReference(currentClass, methodName, argCount, classCtx.filePath);
        }

        var scope = callExpr.getScope().get();

        if (scope instanceof ThisExpr) {
            return new MethodReference(currentClass, methodName, argCount, classCtx.filePath);
        }

        if (scope instanceof NameExpr nameExpr) {
            String varName = nameExpr.getNameAsString();
            String resolvedType = resolveVariableType(varName, localTypes, classCtx, allClasses);
            if (resolvedType != null) {
                Path targetFile = allClasses.containsKey(resolvedType)
                        ? allClasses.get(resolvedType).filePath : null;
                return new MethodReference(resolvedType, methodName, argCount, targetFile);
            }

            if (allClasses.containsKey(varName)) {
                return new MethodReference(varName, methodName, argCount, allClasses.get(varName).filePath);
            }
        }

        return MethodReference.unresolved(methodName, argCount);
    }

    private String resolveVariableType(String varName, Map<String, String> localTypes,
                                       ClassContext classCtx,
                                       Map<String, ClassContext> allClasses) {
        String type = localTypes.get(varName);
        if (type != null) return stripGenerics(type);

        type = classCtx.fieldTypes.get(varName);
        if (type != null) return stripGenerics(type);

        return null;
    }

    private Map<String, String> buildLocalTypeMap(MethodDeclaration md) {
        Map<String, String> types = new HashMap<>();

        for (Parameter param : md.getParameters()) {
            types.put(param.getNameAsString(), param.getTypeAsString());
        }

        for (VariableDeclarator var : md.findAll(VariableDeclarator.class)) {
            Node parent = var.getParentNode().orElse(null);
            if (parent != null && !(parent instanceof FieldDeclaration)) {
                types.put(var.getNameAsString(), var.getTypeAsString());
            }
        }

        return types;
    }

    private String stripGenerics(String type) {
        int idx = type.indexOf('<');
        return idx > 0 ? type.substring(0, idx) : type;
    }

    private CompilationUnit parseFile(Path path) {
        try {
            String source = Files.readString(path);
            var result = javaParser.parse(source);
            if (result.getResult().isPresent()) {
                if (!result.isSuccessful()) {
                    logger.debug("Parsed {} with {} warning(s)", path.getFileName(), result.getProblems().size());
                }
                return result.getResult().get();
            }
            logger.warn("Could not parse {}", path.getFileName());
        } catch (IOException ex) {
            logger.error("Error reading {}: {}", path, ex.getMessage());
        }
        return null;
    }

    private static class ClassContext {
        final Path filePath;
        final Map<String, String> fieldTypes = new HashMap<>();

        ClassContext(Path filePath) {
            this.filePath = filePath;
        }
    }
}
