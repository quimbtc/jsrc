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
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
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

        // Single pass: parse each file once, register classes and analyze calls together.
        // Receiver types from files not yet processed resolve to "?" and are fixed up
        // in the post-processing step (resolveUnknownCallees / resolveFieldMarkers).
        for (Path file : javaFiles) {
            CompilationUnit cu = parseFile(file);
            if (cu == null) continue;
            registerClasses(cu, file, classContexts);
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
                    int paramCount = com.jsrc.app.util.SignatureUtils.countParams(im.signature());
                    MethodReference ref = new MethodReference(ic.name(), im.name(), paramCount, null);
                    allMethods.add(ref);
                    methodsByName.computeIfAbsent(im.name(), k -> new HashSet<>()).add(ref);
                }
            }

            // Load call edges
            for (var edge : entry.callEdges()) {
                // Use callerParamCount from edge, fallback to registered method
                MethodReference caller = edge.callerParamCount() >= 0
                        ? new MethodReference(edge.callerClass(), edge.callerMethod(), edge.callerParamCount(), null)
                        : resolveRegistered(edge.callerClass(), edge.callerMethod());
                MethodReference callee = new MethodReference(edge.calleeClass(), edge.calleeMethod(),
                        edge.argCount(), null);
                MethodCall call = new MethodCall(caller, callee, edge.line());

                allMethods.add(caller);
                methodsByName.computeIfAbsent(edge.callerMethod(), k -> new HashSet<>()).add(caller);

                calleeIndex.computeIfAbsent(caller, k -> new HashSet<>()).add(call);
                callerIndex.computeIfAbsent(callee, k -> new HashSet<>()).add(call);
            }
        }

        // Post-process: resolve "?" callee classes using return type map
        resolveUnknownCallees(entries);

        logger.info("Call graph loaded from index: {} methods, {} call edges",
                allMethods.size(), callerIndex.values().stream().mapToInt(Set::size).sum());
    }

    /**
     * Resolves callee class "?" by looking up the return type of the method
     * that produces the receiver. Runs iteratively to handle chained calls like
     * {@code fto.getExplotacion().getIdiomaDefecto().getIdioma()}.
     * <p>
     * Also resolves "?field:OwnerType.fieldName" markers from field access chains.
     * <p>
     * Pass 1: resolves ?.getIdiomaDefecto() → Explotacion.getIdiomaDefecto()
     * (because getExplotacion() returns Explotacion and is on the same line).
     * Pass 2: resolves ?.getIdioma() → IdiomaDefecto.getIdioma()
     * (because getIdiomaDefecto() was resolved in pass 1 and returns IdiomaDefecto).
     */
    private void resolveUnknownCallees(List<com.jsrc.app.index.IndexEntry> entries) {
        // Build class name → qualified name map (for all indexed classes)
        Map<String, Set<String>> simpleToQualified = new HashMap<>();
        for (var entry : entries) {
            for (var ic : entry.classes()) {
                simpleToQualified.computeIfAbsent(ic.name(), k -> new HashSet<>())
                        .add(ic.qualifiedName());
            }
        }

        // Build field type map: "ClassName.fieldName" → field type (simple name)
        Map<String, String> fieldTypeMap = new HashMap<>();
        for (var entry : entries) {
            for (var ic : entry.classes()) {
                for (var f : ic.fields()) {
                    fieldTypeMap.put(ic.name() + "." + f.name(), f.type());
                }
            }
        }

        // Resolve ?field: markers before return-type resolution
        resolveFieldMarkers(fieldTypeMap);

        // Build return type map: "ClassName.methodName" → resolved simple return type
        Map<String, String> returnTypes = new HashMap<>();
        for (var entry : entries) {
            for (var ic : entry.classes()) {
                for (var im : ic.methods()) {
                    if (im.returnType() != null && !im.returnType().isEmpty()
                            && !"void".equals(im.returnType())) {
                        String rt = im.returnType();
                        int genIdx = rt.indexOf('<');
                        if (genIdx > 0) rt = rt.substring(0, genIdx);

                        String resolved = resolveTypeViaImports(rt, ic.imports(), ic.packageName(), simpleToQualified);
                        returnTypes.put(ic.name() + "." + im.name(), resolved);
                    }
                }
            }
        }

        if (returnTypes.isEmpty()) return;

        // Iterate until no more resolutions (handles chained calls)
        for (int pass = 0; pass < 5; pass++) {
            boolean changed = resolvePass(returnTypes, simpleToQualified);
            if (!changed) break;
            logger.debug("Return type resolution pass {} completed", pass + 1);
        }
    }

    /**
     * Resolves "?field:" and "?ret:" callee class markers in the call graph.
     * Delegates marker parsing to {@link com.jsrc.app.index.EdgeResolver#resolveMarker}.
     */
    private void resolveFieldMarkers(Map<String, String> fieldTypeMap) {
        Map<String, String> returnTypeMap = new HashMap<>();

        for (int pass = 0; pass < 5; pass++) {
            boolean changed = false;
            Map<MethodReference, Set<MethodCall>> newCalleeIndex = new HashMap<>();

            for (var callerEntry : calleeIndex.entrySet()) {
                MethodReference caller = callerEntry.getKey();
                Set<MethodCall> calls = callerEntry.getValue();
                Set<MethodCall> updatedCalls = new HashSet<>();

                for (MethodCall call : calls) {
                    String calleeClass = call.callee().className();
                    if (calleeClass.startsWith("?field:") || calleeClass.startsWith("?ret:")) {
                        String resolved = com.jsrc.app.index.EdgeResolver.resolveMarker(
                                calleeClass, fieldTypeMap, returnTypeMap);
                        if (resolved != null && !resolved.startsWith("?")) {
                            MethodReference newCallee = new MethodReference(
                                    resolved, call.callee().methodName(),
                                    call.callee().parameterCount(), null);
                            MethodCall newCall = new MethodCall(caller, newCallee, call.line());
                            updatedCalls.add(newCall);

                            callerIndex.getOrDefault(call.callee(), Collections.emptySet()).remove(call);
                            callerIndex.computeIfAbsent(newCallee, k -> new HashSet<>()).add(newCall);
                            allMethods.add(newCallee);
                            methodsByName.computeIfAbsent(newCallee.methodName(), k -> new HashSet<>()).add(newCallee);
                            changed = true;
                            continue;
                        }
                    }
                    updatedCalls.add(call);
                }
                newCalleeIndex.put(caller, updatedCalls);
            }

            if (changed) {
                calleeIndex.clear();
                calleeIndex.putAll(newCalleeIndex);
                callerIndex.entrySet().removeIf(e -> e.getValue().isEmpty());
            } else {
                break;
            }
        }
    }

    /**
     * Single pass of unknown callee resolution.
     * Returns true if any callees were resolved.
     */
    private boolean resolvePass(Map<String, String> returnTypes,
                                Map<String, Set<String>> qualifiedNames) {
        Map<MethodReference, Set<MethodCall>> newCalleeIndex = new HashMap<>();
        boolean changed = false;

        for (var callerEntry : calleeIndex.entrySet()) {
            MethodReference caller = callerEntry.getKey();
            Set<MethodCall> calls = callerEntry.getValue();

            // Collect all resolved calls on each line (for return type lookup)
            Map<Integer, List<MethodCall>> resolvedByLine = new HashMap<>();
            for (MethodCall call : calls) {
                if (!"?".equals(call.callee().className())) {
                    resolvedByLine.computeIfAbsent(call.line(), k -> new java.util.ArrayList<>()).add(call);
                }
            }

            Set<MethodCall> updatedCalls = new HashSet<>();
            for (MethodCall call : calls) {
                if (!"?".equals(call.callee().className())) {
                    updatedCalls.add(call);
                    continue;
                }

                String resolvedClass = resolveCalleeClass(call, resolvedByLine, returnTypes, qualifiedNames);

                if (resolvedClass != null) {
                    MethodReference newCallee = new MethodReference(
                            resolvedClass, call.callee().methodName(),
                            call.callee().parameterCount(), null);
                    MethodCall newCall = new MethodCall(caller, newCallee, call.line());
                    updatedCalls.add(newCall);

                    // Update callerIndex
                    callerIndex.getOrDefault(call.callee(), Collections.emptySet()).remove(call);
                    callerIndex.computeIfAbsent(newCallee, k -> new HashSet<>()).add(newCall);

                    allMethods.add(newCallee);
                    methodsByName.computeIfAbsent(newCallee.methodName(), k -> new HashSet<>()).add(newCallee);
                    changed = true;
                } else {
                    updatedCalls.add(call);
                }
            }

            newCalleeIndex.put(caller, updatedCalls);
        }

        if (changed) {
            calleeIndex.clear();
            calleeIndex.putAll(newCalleeIndex);
            callerIndex.entrySet().removeIf(e -> e.getValue().isEmpty());
        }
        return changed;
    }

    /**
     * Tries to resolve a "?" callee class using return types of other calls on the same line,
     * or by unique method name match across the codebase.
     * <p>
     * When the return type is a qualified name (e.g. "com.app.Explotacion"), uses it
     * to disambiguate classes with the same simple name in different packages.
     *
     * @param qualifiedNames map of simple class name → set of qualified names (for disambiguation)
     */
    private String resolveCalleeClass(MethodCall call,
                                       Map<Integer, List<MethodCall>> resolvedByLine,
                                       Map<String, String> returnTypes,
                                       Map<String, Set<String>> qualifiedNames) {
        // Strategy 1: same-line calls whose return type declares this method
        List<MethodCall> sameLine = resolvedByLine.getOrDefault(call.line(), List.of());
        for (MethodCall resolved : sameLine) {
            String rt = returnTypes.get(resolved.callee().className() + "." + resolved.callee().methodName());
            if (rt != null) {
                String simpleRt = rt.contains(".") ? rt.substring(rt.lastIndexOf('.') + 1) : rt;

                // Verify the return type class has the target method
                Set<MethodReference> candidates = methodsByName.getOrDefault(call.callee().methodName(), Set.of());
                boolean hasMethod = candidates.stream()
                        .anyMatch(m -> m.className().equals(simpleRt));
                if (!hasMethod) continue;

                // If multiple classes share the simple name, use the qualified return type
                // to pick the correct one by checking which class has this method in returnTypes
                Set<String> qualifieds = qualifiedNames.getOrDefault(simpleRt, Set.of());
                if (qualifieds.size() > 1 && rt.contains(".")) {
                    // The return type map keys use simple names. Check if the method exists
                    // specifically under a class with the correct qualified name by verifying
                    // returnTypes contains an entry for this class.method
                    String checkKey = simpleRt + "." + call.callee().methodName();
                    String methodReturnType = returnTypes.get(checkKey);
                    if (methodReturnType != null || hasMethod) {
                        // Accept only if the qualified RT is among known qualifieds
                        if (qualifieds.contains(rt)) {
                            return simpleRt;
                        }
                    }
                } else {
                    return simpleRt;
                }
            }
        }

        // Strategy 2: unique method name — only one class has this method
        Set<MethodReference> candidates = methodsByName.get(call.callee().methodName());
        if (candidates != null) {
            Set<String> classes = new HashSet<>();
            for (MethodReference c : candidates) {
                if (!"?".equals(c.className())) classes.add(c.className());
            }
            if (classes.size() == 1) {
                return classes.iterator().next();
            }
        }

        return null;
    }

    /**
     * Resolves a simple return type name to a qualified name using the declaring
     * class's imports. E.g. "Explotacion" → "com.agbar.occam.negocio.modelos.Explotacion"
     * if the declaring class imports that package.
     *
     * @param simpleType        simple type name from return type
     * @param imports           import statements of the declaring class
     * @param declaringPkg      package of the declaring class
     * @param simpleToQualified map of simple name → set of qualified names
     * @return qualified name if resolvable, otherwise the simple name
     */
    private static String resolveTypeViaImports(String simpleType, java.util.List<String> imports,
                                                 String declaringPkg,
                                                 Map<String, Set<String>> simpleToQualified) {
        if (simpleType == null || simpleType.isEmpty()) return simpleType;
        if (simpleType.contains(".")) return simpleType;

        // Check explicit imports: import com.app.Explotacion;
        for (String imp : imports) {
            if (imp.endsWith("." + simpleType)) {
                return imp;
            }
        }

        // Check wildcard imports: import com.app.*;
        Set<String> qualifieds = simpleToQualified.getOrDefault(simpleType, Set.of());
        for (String imp : imports) {
            if (imp.endsWith(".*")) {
                String pkg = imp.substring(0, imp.length() - 2);
                String candidate = pkg + "." + simpleType;
                if (qualifieds.contains(candidate)) {
                    return candidate;
                }
            }
        }

        // Same package?
        if (declaringPkg != null && !declaringPkg.isEmpty()) {
            String samePackage = declaringPkg + "." + simpleType;
            if (qualifieds.contains(samePackage)) {
                return samePackage;
            }
        }

        // Only one qualified name exists — use it
        if (qualifieds.size() == 1) {
            return qualifieds.iterator().next();
        }

        return simpleType;
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

    /**
     * Creates an immutable {@link CallGraph} snapshot from the current builder state.
     * Call after {@link #build(List)} or {@link #loadFromIndex(List)}.
     */
    public CallGraph toCallGraph() {
        // Deep-copy sets to ensure immutability
        Map<MethodReference, Set<MethodCall>> callerCopy = new HashMap<>();
        for (var e : callerIndex.entrySet()) {
            callerCopy.put(e.getKey(), Set.copyOf(e.getValue()));
        }
        Map<MethodReference, Set<MethodCall>> calleeCopy = new HashMap<>();
        for (var e : calleeIndex.entrySet()) {
            calleeCopy.put(e.getKey(), Set.copyOf(e.getValue()));
        }
        Map<String, Set<MethodReference>> byNameCopy = new HashMap<>();
        for (var e : methodsByName.entrySet()) {
            byNameCopy.put(e.getKey(), Set.copyOf(e.getValue()));
        }
        return CallGraph.of(callerCopy, calleeCopy, Set.copyOf(allMethods), byNameCopy);
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

            // Register constructors as methods named after the class
            for (ConstructorDeclaration cd : cid.getConstructors()) {
                MethodReference ref = new MethodReference(
                        className, className,
                        cd.getParameters().size(), file);
                allMethods.add(ref);
                methodsByName.computeIfAbsent(className, k -> new HashSet<>()).add(ref);
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

                analyzeCallsInBody(caller, md, className, localTypes, classCtx, classContexts);
            }

            // Analyze constructor bodies
            for (ConstructorDeclaration cd : cid.getConstructors()) {
                MethodReference caller = new MethodReference(
                        className, className,
                        cd.getParameters().size(), file);

                Map<String, String> localTypes = buildLocalTypeMap(cd);
                analyzeCallsInBody(caller, cd, className, localTypes, classCtx, classContexts);
            }
        }
    }

    private void analyzeCallsInBody(MethodReference caller, Node body, String className,
                                      Map<String, String> localTypes, ClassContext classCtx,
                                      Map<String, ClassContext> classContexts) {
        // Method calls
        for (MethodCallExpr callExpr : body.findAll(MethodCallExpr.class)) {
            MethodReference callee = resolveCallee(callExpr, className, localTypes, classCtx, classContexts);
            int line = callExpr.getBegin().map(p -> p.line).orElse(-1);
            MethodCall call = new MethodCall(caller, callee, line);
            calleeIndex.computeIfAbsent(caller, k -> new HashSet<>()).add(call);
            callerIndex.computeIfAbsent(callee, k -> new HashSet<>()).add(call);
        }

        // Constructor invocations: new Foo(...)
        for (ObjectCreationExpr newExpr : body.findAll(ObjectCreationExpr.class)) {
            String targetClass = newExpr.getType().getNameAsString();
            MethodReference callee = new MethodReference(targetClass, targetClass,
                    newExpr.getArguments().size(), null);
            int line = newExpr.getBegin().map(p -> p.line).orElse(-1);
            MethodCall call = new MethodCall(caller, callee, line);
            calleeIndex.computeIfAbsent(caller, k -> new HashSet<>()).add(call);
            callerIndex.computeIfAbsent(callee, k -> new HashSet<>()).add(call);
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

        if (scope instanceof FieldAccessExpr fae) {
            String resolvedType = resolveFieldAccessType(fae, currentClass, localTypes, classCtx, allClasses);
            if (resolvedType != null) {
                Path targetFile = allClasses.containsKey(resolvedType)
                        ? allClasses.get(resolvedType).filePath : null;
                return new MethodReference(resolvedType, methodName, argCount, targetFile);
            }
        }

        return MethodReference.unresolved(methodName, argCount);
    }

    /**
     * Resolves the type of a field access expression like {@code obj.field}.
     * Determines the type of {@code obj}, then looks up the field type in that class's context.
     */
    private String resolveFieldAccessType(FieldAccessExpr fae, String currentClass,
                                           Map<String, String> localTypes,
                                           ClassContext classCtx,
                                           Map<String, ClassContext> allClasses) {
        String fieldName = fae.getNameAsString();
        Expression objExpr = fae.getScope();

        String objType = resolveExpressionType(objExpr, currentClass, localTypes, classCtx, allClasses);
        if (objType == null) return null;

        // Look up the field type in the resolved class
        ClassContext ownerCtx = allClasses.get(objType);
        if (ownerCtx != null) {
            String fieldType = ownerCtx.fieldTypes.get(fieldName);
            if (fieldType != null) return stripGenerics(fieldType);
        }
        return null;
    }

    /**
     * Resolves the type of an arbitrary expression: variable, this, field access chain.
     */
    private String resolveExpressionType(Expression expr, String currentClass,
                                          Map<String, String> localTypes,
                                          ClassContext classCtx,
                                          Map<String, ClassContext> allClasses) {
        if (expr instanceof ThisExpr) return currentClass;
        if (expr instanceof NameExpr ne) {
            String varName = ne.getNameAsString();
            String resolved = resolveVariableType(varName, localTypes, classCtx, allClasses);
            if (resolved != null) return resolved;
            if (allClasses.containsKey(varName)) return varName;
            return null;
        }
        if (expr instanceof FieldAccessExpr fae) {
            return resolveFieldAccessType(fae, currentClass, localTypes, classCtx, allClasses);
        }
        if (expr instanceof MethodCallExpr mce) {
            // For method().field.method() chains: resolve receiver, then lookup return type
            // This requires return type info which we don't have in build() pass
            // Return null — will be resolved in post-processing
            return null;
        }
        return null;
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

    /**
     * Builds a map of variable/parameter name → type for a method or constructor.
     * Both extend CallableDeclaration, sharing the same parameter/local structure.
     */
    private Map<String, String> buildLocalTypeMap(com.github.javaparser.ast.body.CallableDeclaration<?> callable) {
        Map<String, String> types = new HashMap<>();
        for (Parameter param : callable.getParameters()) {
            types.put(param.getNameAsString(), param.getTypeAsString());
        }
        for (VariableDeclarator var : callable.findAll(VariableDeclarator.class)) {
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

    /**
     * Counts parameters from a method signature string.
     * E.g. "public void foo(String s, int x)" → 2, "void bar()" → 0.
     */
    /**
     * Finds a registered MethodReference by class+method name.
     * Returns the first match, or a new MR with -1 if not found.
     */
    private MethodReference resolveRegistered(String className, String methodName) {
        Set<MethodReference> byName = methodsByName.get(methodName);
        if (byName != null) {
            for (MethodReference ref : byName) {
                if (ref.className().equals(className)) return ref;
            }
        }
        return new MethodReference(className, methodName, -1, null);
    }

    private static class ClassContext {
        final Path filePath;
        final Map<String, String> fieldTypes = new HashMap<>();

        ClassContext(Path filePath) {
            this.filePath = filePath;
        }
    }
}
