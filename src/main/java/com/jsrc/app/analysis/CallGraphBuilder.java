package com.jsrc.app.analysis;

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
     * Uses two passes to avoid holding all ASTs in memory:
     * <ol>
     *   <li>Pass 1: register classes and fields (lightweight context only, AST discarded)</li>
     *   <li>Pass 2: re-parse each file and analyze method calls (AST discarded after each file)</li>
     * </ol>
     */
    public void build(List<Path> javaFiles) {
        callerIndex.clear();
        calleeIndex.clear();
        allMethods.clear();
        methodsByName.clear();

        Map<String, ClassContext> classContexts = new HashMap<>();

        // Pass 1: register class contexts (fields, method signatures) — no AST retained
        for (Path file : javaFiles) {
            CompilationUnit cu = parseFile(file);
            if (cu == null) continue;
            registerClasses(cu, file, classContexts);
            // cu goes out of scope → GC can reclaim
        }

        // Pass 2: re-parse and analyze calls one file at a time
        for (Path file : javaFiles) {
            CompilationUnit cu = parseFile(file);
            if (cu == null) continue;
            analyzeMethodCalls(cu, file, classContexts);
            // cu goes out of scope → GC can reclaim
        }

        logger.info("Call graph built: {} methods, {} call edges",
                allMethods.size(), callerIndex.values().stream().mapToInt(Set::size).sum());
    }

    /**
     * Loads the call graph from pre-computed index entries.
     * Much faster than {@link #build(List)} — no file I/O or parsing needed.
     */
    public void loadFromIndex(List<com.jsrc.app.index.IndexEntry> entries) {
        callerIndex.clear();
        calleeIndex.clear();
        allMethods.clear();
        methodsByName.clear();

        for (var entry : entries) {
            // Register methods from classes
            for (var ic : entry.classes()) {
                for (var im : ic.methods()) {
                    MethodReference ref = new MethodReference(ic.name(), im.name(), -1, null);
                    allMethods.add(ref);
                    methodsByName.computeIfAbsent(im.name(), k -> new HashSet<>()).add(ref);
                }
            }

            // Load call edges
            for (var edge : entry.callEdges()) {
                MethodReference caller = new MethodReference(edge.callerClass(), edge.callerMethod(), -1, null);
                MethodReference callee = new MethodReference(edge.calleeClass(), edge.calleeMethod(), -1, null);
                MethodCall call = new MethodCall(caller, callee, edge.line());

                allMethods.add(caller);
                methodsByName.computeIfAbsent(edge.callerMethod(), k -> new HashSet<>()).add(caller);

                calleeIndex.computeIfAbsent(caller, k -> new HashSet<>()).add(call);
                callerIndex.computeIfAbsent(callee, k -> new HashSet<>()).add(call);
            }
        }

        logger.info("Call graph loaded from index: {} methods, {} call edges",
                allMethods.size(), callerIndex.values().stream().mapToInt(Set::size).sum());
    }

    /**
     * Adds a single call edge to the graph (e.g. reflective calls from InvokerResolver).
     */
    public void addEdge(MethodCall call) {
        MethodReference caller = call.caller();
        MethodReference callee = call.callee();
        allMethods.add(caller);
        allMethods.add(callee);
        methodsByName.computeIfAbsent(caller.methodName(), k -> new HashSet<>()).add(caller);
        methodsByName.computeIfAbsent(callee.methodName(), k -> new HashSet<>()).add(callee);
        calleeIndex.computeIfAbsent(caller, k -> new HashSet<>()).add(call);
        callerIndex.computeIfAbsent(callee, k -> new HashSet<>()).add(call);
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
            logger.debug("Could not parse {}", path.getFileName());
        } catch (IOException ex) {
            logger.debug("Error reading {}: {}", path, ex.getMessage());
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
