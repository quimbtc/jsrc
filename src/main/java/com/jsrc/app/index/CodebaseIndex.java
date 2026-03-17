package com.jsrc.app.index;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.jsrc.app.output.JsonWriter;
import com.jsrc.app.parser.CodeParser;
import com.jsrc.app.parser.model.ClassInfo;
import com.jsrc.app.parser.model.MethodInfo;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.ThisExpr;

/**
 * Builds and persists a codebase index for fast lookups.
 * Index is stored as JSON in {@code .jsrc/index.json}.
 */
public class CodebaseIndex {

    private static final Logger logger = LoggerFactory.getLogger(CodebaseIndex.class);
    private static final String INDEX_DIR = ".jsrc";
    private static final String INDEX_FILE = "index.json";

    private final List<IndexEntry> entries;

    public CodebaseIndex() {
        this.entries = new ArrayList<>();
    }

    public CodebaseIndex(List<IndexEntry> entries) {
        this.entries = new ArrayList<>(entries);
    }

    public List<IndexEntry> getEntries() {
        return entries;
    }

    /**
     * Builds the index by parsing all given files.
     * Skips files whose content hash matches an existing entry (incremental).
     *
     * @param parser    parser to use
     * @param files     files to index
     * @param sourceRoot source root for relative paths
     * @param existing  existing index entries (for incremental update)
     * @return number of files re-indexed
     */
    public int build(CodeParser parser, List<Path> files, Path sourceRoot,
                     List<IndexEntry> existing) {
        return build(parser, files, sourceRoot, existing, List.of());
    }

    /**
     * Builds the index with optional invoker definitions for reflective edge extraction.
     */
    public int build(CodeParser parser, List<Path> files, Path sourceRoot,
                     List<IndexEntry> existing,
                     List<com.jsrc.app.config.ArchitectureConfig.InvokerDef> invokers) {
        Map<String, IndexEntry> existingByPath = new LinkedHashMap<>();
        for (IndexEntry e : existing) {
            existingByPath.put(e.path(), e);
        }

        entries.clear();
        int reindexed = 0;
        var edgeParser = new JavaParser(); // reused across files

        for (Path file : files) {
            String relativePath = sourceRoot.relativize(file).toString();
            try {
                byte[] content = Files.readAllBytes(file);
                String hash = com.jsrc.app.util.Hashing.sha256(content);
                long lastModified = Files.getLastModifiedTime(file).toMillis();

                IndexEntry prev = existingByPath.get(relativePath);
                if (prev != null && prev.contentHash().equals(hash)) {
                    entries.add(prev);
                    continue;
                }

                // Need to re-index
                List<ClassInfo> classes = parser.parseClasses(file);

                // Extract imports from file for return type resolution
                List<String> fileImports = extractImports(file, edgeParser);

                List<IndexedClass> indexed = classes.stream()
                        .map(ci -> toIndexedClass(ci, file, parser, fileImports))
                        .toList();

                // Extract call edges (direct + reflective)
                List<CallEdge> edges = new ArrayList<>(extractCallEdges(file, edgeParser));
                if (!invokers.isEmpty()) {
                    edges.addAll(extractReflectiveEdges(file, edgeParser, invokers));
                }

                entries.add(new IndexEntry(relativePath, hash, lastModified, indexed, edges));
                reindexed++;
            } catch (IOException ex) {
                logger.error("Error indexing {}: {}", file, ex.getMessage());
            }
        }

        // Post-build: resolve ?field: markers using cross-class field type map
        resolveFieldMarkersInEntries();

        return reindexed;
    }

    /**
     * Resolves "?field:" and "?ret:" callee class markers in all index entries.
     * Uses field type and return type information from all indexed classes.
     */
    private void resolveFieldMarkersInEntries() {
        // Build field type map: "ClassName.fieldName" → type
        Map<String, String> fieldTypeMap = new HashMap<>();
        // Build return type map: "ClassName.methodName" → return type
        Map<String, String> returnTypeMap = new HashMap<>();
        for (IndexEntry entry : entries) {
            for (IndexedClass ic : entry.classes()) {
                for (IndexedField f : ic.fields()) {
                    fieldTypeMap.put(ic.name() + "." + f.name(), f.type());
                }
                for (IndexedMethod im : ic.methods()) {
                    if (im.returnType() != null && !im.returnType().isEmpty()
                            && !"void".equals(im.returnType())) {
                        String rt = im.returnType();
                        int genIdx = rt.indexOf('<');
                        if (genIdx > 0) rt = rt.substring(0, genIdx);
                        returnTypeMap.put(ic.name() + "." + im.name(), rt);
                    }
                }
            }
        }
        if (fieldTypeMap.isEmpty() && returnTypeMap.isEmpty()) return;

        // Resolve markers iteratively (nested field/return type chains)
        for (int pass = 0; pass < 5; pass++) {
            boolean changed = false;
            List<IndexEntry> newEntries = new ArrayList<>();
            for (IndexEntry entry : entries) {
                List<CallEdge> newEdges = new ArrayList<>();
                boolean entryChanged = false;
                for (CallEdge edge : entry.callEdges()) {
                    if (edge.calleeClass().startsWith("?field:") || edge.calleeClass().startsWith("?ret:")) {
                        String resolved = resolveMarker(edge.calleeClass(), fieldTypeMap, returnTypeMap);
                        if (resolved != null && !resolved.startsWith("?")) {
                            newEdges.add(new CallEdge(edge.callerClass(), edge.callerMethod(),
                                    edge.callerParamCount(), resolved, edge.calleeMethod(),
                                    edge.line(), edge.argCount()));
                            entryChanged = true;
                            changed = true;
                            continue;
                        }
                    }
                    newEdges.add(edge);
                }
                newEntries.add(entryChanged
                        ? new IndexEntry(entry.path(), entry.contentHash(), entry.lastModified(),
                                entry.classes(), newEdges)
                        : entry);
            }
            entries.clear();
            entries.addAll(newEntries);
            if (!changed) break;
        }
    }

    /**
     * Resolves a marker string to a concrete type.
     * Supports "?field:OwnerType.fieldName" and "?ret:ClassName.methodName".
     */
    private static String resolveMarker(String marker,
                                         Map<String, String> fieldTypeMap,
                                         Map<String, String> returnTypeMap) {
        if (marker.startsWith("?field:")) {
            String payload = marker.substring("?field:".length());
            int dotIdx = payload.lastIndexOf('.');
            if (dotIdx < 0) return null;

            String ownerType = payload.substring(0, dotIdx);
            String fieldName = payload.substring(dotIdx + 1);

            // If owner is itself a marker, resolve recursively
            if (ownerType.startsWith("?")) {
                ownerType = resolveMarker(ownerType, fieldTypeMap, returnTypeMap);
                if (ownerType == null || ownerType.startsWith("?")) return null;
            }

            return fieldTypeMap.get(ownerType + "." + fieldName);
        }

        if (marker.startsWith("?ret:")) {
            String payload = marker.substring("?ret:".length());
            int dotIdx = payload.lastIndexOf('.');
            if (dotIdx < 0) return null;

            String ownerType = payload.substring(0, dotIdx);
            String methodName = payload.substring(dotIdx + 1);

            // If owner is itself a marker, resolve recursively
            if (ownerType.startsWith("?")) {
                ownerType = resolveMarker(ownerType, fieldTypeMap, returnTypeMap);
                if (ownerType == null || ownerType.startsWith("?")) return null;
            }

            return returnTypeMap.get(ownerType + "." + methodName);
        }

        return marker;
    }

    /**
     * Persists the index to {@code .jsrc/index.json} under the given root.
     */
    public void save(Path projectRoot) throws IOException {
        Path indexDir = projectRoot.resolve(INDEX_DIR);
        Files.createDirectories(indexDir);
        Path indexFile = indexDir.resolve(INDEX_FILE);

        List<Map<String, Object>> serialized = entries.stream()
                .map(this::entryToMap)
                .toList();

        String json = JsonWriter.toJson(serialized);
        Files.writeString(indexFile, json, StandardCharsets.UTF_8);
        logger.info("Index saved: {} entries to {}", entries.size(), indexFile);
    }

    /**
     * Loads an existing index from disk.
     *
     * @return list of entries, or empty if no index exists
     */
    @SuppressWarnings("unchecked")
    public static List<IndexEntry> load(Path projectRoot) {
        Path indexFile = projectRoot.resolve(INDEX_DIR).resolve(INDEX_FILE);
        if (!Files.exists(indexFile)) {
            return List.of();
        }
        try {
            String json = Files.readString(indexFile, java.nio.charset.StandardCharsets.UTF_8);
            Object parsed = com.jsrc.app.output.JsonReader.parse(json);
            if (!(parsed instanceof List<?> rawList)) {
                logger.warn("Index file is not a JSON array: {}", indexFile);
                return List.of();
            }

            List<IndexEntry> result = new ArrayList<>();
            for (Object item : rawList) {
                if (item instanceof Map<?, ?> map) {
                    result.add(mapToEntry((Map<String, Object>) map));
                }
            }
            logger.info("Loaded index: {} entries from {}", result.size(), indexFile);
            return result;
        } catch (IOException e) {
            logger.error("Error reading index {}: {}", indexFile, e.getMessage());
            return List.of();
        } catch (Exception e) {
            logger.warn("Error parsing index {}: {}", indexFile, e.getMessage());
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private static IndexEntry mapToEntry(Map<String, Object> map) {
        String path = (String) map.getOrDefault("path", "");
        String hash = (String) map.getOrDefault("contentHash", "");
        long lastModified = map.get("lastModified") instanceof Number n ? n.longValue() : 0;

        List<IndexedClass> classes = new ArrayList<>();
        Object classesRaw = map.get("classes");
        if (classesRaw instanceof List<?> classList) {
            for (Object c : classList) {
                if (c instanceof Map<?, ?> cm) {
                    classes.add(mapToIndexedClass((Map<String, Object>) cm));
                }
            }
        }
        List<CallEdge> callEdges = new ArrayList<>();
        Object edgesRaw = map.get("callEdges");
        if (edgesRaw instanceof List<?> edgeList) {
            for (Object e : edgeList) {
                if (e instanceof Map<?, ?> em) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> edgeMap = (Map<String, Object>) em;
                    int callerParamCount = edgeMap.containsKey("callerParamCount") ? intVal(edgeMap, "callerParamCount") : -1;
                    int argCount = edgeMap.containsKey("argCount") ? intVal(edgeMap, "argCount") : -1;
                    callEdges.add(new CallEdge(
                            str(edgeMap, "callerClass"), str(edgeMap, "callerMethod"),
                            callerParamCount,
                            str(edgeMap, "calleeClass"), str(edgeMap, "calleeMethod"),
                            intVal(edgeMap, "line"), argCount));
                }
            }
        }
        return new IndexEntry(path, hash, lastModified, classes, callEdges);
    }

    @SuppressWarnings("unchecked")
    private static IndexedClass mapToIndexedClass(Map<String, Object> map) {
        String name = str(map, "name");
        String pkg = str(map, "packageName");
        int startLine = intVal(map, "startLine");
        int endLine = intVal(map, "endLine");
        boolean isInterface = bool(map, "isInterface");
        boolean isAbstract = bool(map, "isAbstract");
        List<String> superClass = strList(map.get("superClass"));
        List<String> interfaces = strList(map.get("interfaces"));
        List<String> annotations = strList(map.get("annotations"));
        List<String> imports = strList(map.get("imports"));

        List<IndexedMethod> methods = new ArrayList<>();
        Object methodsRaw = map.get("methods");
        if (methodsRaw instanceof List<?> ml) {
            for (Object m : ml) {
                if (m instanceof Map<?, ?> mm) {
                    methods.add(mapToIndexedMethod((Map<String, Object>) mm));
                }
            }
        }

        List<IndexedField> fields = new ArrayList<>();
        Object fieldsRaw = map.get("fields");
        if (fieldsRaw instanceof List<?> fl) {
            for (Object f : fl) {
                if (f instanceof Map<?, ?> fm) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> fieldMap = (Map<String, Object>) fm;
                    fields.add(new IndexedField(str(fieldMap, "name"), str(fieldMap, "type")));
                }
            }
        }

        return new IndexedClass(name, pkg, startLine, endLine,
                isInterface, isAbstract, superClass, interfaces,
                methods, annotations, imports, fields);
    }

    @SuppressWarnings("unchecked")
    private static IndexedMethod mapToIndexedMethod(Map<String, Object> map) {
        return new IndexedMethod(
                str(map, "name"), str(map, "signature"),
                intVal(map, "startLine"), intVal(map, "endLine"),
                str(map, "returnType"), strList(map.get("annotations")));
    }

    private static String str(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v instanceof String s ? s : "";
    }

    private static int intVal(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v instanceof Number n ? n.intValue() : 0;
    }

    private static boolean bool(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v instanceof Boolean b && b;
    }

    private static List<String> strList(Object raw) {
        if (raw instanceof List<?> list) {
            return list.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .toList();
        }
        return List.of();
    }

    // ---- internal ----

    /**
     * Extracts call edges from a Java file using JavaParser.
     * Resolves callee class names using field types, parameter types,
     * and local variable types for accurate call graph edges.
     */
    private List<CallEdge> extractCallEdges(Path file, JavaParser jp) {
        List<CallEdge> edges = new ArrayList<>();
        try {
            String source = Files.readString(file);
            var result = jp.parse(source);
            if (!result.getResult().isPresent()) return edges;

            CompilationUnit cu = result.getResult().get();
            for (ClassOrInterfaceDeclaration cid : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                String className = cid.getNameAsString();

                // Collect field types for this class
                Map<String, String> fieldTypes = new HashMap<>();
                for (com.github.javaparser.ast.body.FieldDeclaration field : cid.getFields()) {
                    String fieldType = field.getCommonType().asString();
                    // Strip generics: Map<String,Object> → Map
                    int genIdx = fieldType.indexOf('<');
                    if (genIdx > 0) fieldType = fieldType.substring(0, genIdx);
                    for (com.github.javaparser.ast.body.VariableDeclarator var : field.getVariables()) {
                        fieldTypes.put(var.getNameAsString(), fieldType);
                    }
                }

                for (MethodDeclaration md : cid.getMethods()) {
                    extractEdgesFromCallable(edges, md, className, md.getNameAsString(),
                            md.getParameters().size(), fieldTypes);
                }
                for (com.github.javaparser.ast.body.ConstructorDeclaration cd : cid.getConstructors()) {
                    extractEdgesFromCallable(edges, cd, className, className,
                            cd.getParameters().size(), fieldTypes);
                }
            }
        } catch (Exception ex) {
            logger.debug("Error extracting call edges from {}: {}", file, ex.getMessage());
        }
        return edges;
    }

    /**
     * Extracts call edges from a method or constructor body.
     */
    private static void extractEdgesFromCallable(List<CallEdge> edges,
                                                  com.github.javaparser.ast.body.CallableDeclaration<?> callable,
                                                  String className, String callerMethod,
                                                  int callerParamCount,
                                                  Map<String, String> fieldTypes) {
        Map<String, String> localTypes = new HashMap<>();
        for (com.github.javaparser.ast.body.Parameter param : callable.getParameters()) {
            String pType = param.getTypeAsString();
            int gi = pType.indexOf('<');
            if (gi > 0) pType = pType.substring(0, gi);
            localTypes.put(param.getNameAsString(), pType);
        }
        for (com.github.javaparser.ast.body.VariableDeclarator var : callable.findAll(com.github.javaparser.ast.body.VariableDeclarator.class)) {
            var parent = var.getParentNode().orElse(null);
            if (parent != null && !(parent instanceof com.github.javaparser.ast.body.FieldDeclaration)) {
                String vType = var.getTypeAsString();
                int gi = vType.indexOf('<');
                if (gi > 0) vType = vType.substring(0, gi);
                localTypes.put(var.getNameAsString(), vType);
            }
        }
        for (MethodCallExpr call : callable.findAll(MethodCallExpr.class)) {
            String calleeMethod = call.getNameAsString();
            String calleeClass = resolveCalleeClass(call, className, fieldTypes, localTypes);
            int line = call.getBegin().map(p -> p.line).orElse(-1);
            int argCount = call.getArguments().size();
            edges.add(new CallEdge(className, callerMethod, callerParamCount, calleeClass, calleeMethod, line, argCount));
        }
        for (ObjectCreationExpr newExpr : callable.findAll(ObjectCreationExpr.class)) {
            String targetClass = newExpr.getType().getNameAsString();
            int line = newExpr.getBegin().map(p -> p.line).orElse(-1);
            int argCount = newExpr.getArguments().size();
            edges.add(new CallEdge(className, callerMethod, callerParamCount, targetClass, targetClass, line, argCount));
        }
    }

    /**
     * Resolves the class of the callee in a method call expression.
     * Checks: this → current class, variable → local/param/field type,
     * FieldAccessExpr → field type lookup, static → class name.
     */
    private static String resolveCalleeClass(MethodCallExpr call, String currentClass,
                                              Map<String, String> fieldTypes,
                                              Map<String, String> localTypes) {
        if (call.getScope().isEmpty()) return currentClass;
        var scope = call.getScope().get();
        if (scope instanceof ThisExpr) return currentClass;
        if (scope instanceof NameExpr ne) {
            String varName = ne.getNameAsString();
            // Try local/param types first, then fields
            String type = localTypes.get(varName);
            if (type != null) return type;
            type = fieldTypes.get(varName);
            if (type != null) return type;
            // Could be a static call on a class name
            return varName;
        }
        if (scope instanceof FieldAccessExpr fae) {
            // Resolve: obj.field.method() → resolve obj type, then look up field type
            String fieldName = fae.getNameAsString();
            var objExpr = fae.getScope();

            // Determine the type of the object expression
            String objType = resolveExpressionType(objExpr, currentClass, fieldTypes, localTypes);
            if (objType != null) {
                // Encode as marker for post-load cross-class resolution
                return "?field:" + objType + "." + fieldName;
            }
        }
        return "?";
    }

    /**
     * Resolves the type of an expression (variable, this, field access, method call).
     * Used to determine the owner class of a field access.
     * <p>
     * For method calls without known return type, encodes as "?ret:ClassName.methodName"
     * to be resolved later using the return type map.
     */
    private static String resolveExpressionType(com.github.javaparser.ast.expr.Expression expr,
                                                 String currentClass,
                                                 Map<String, String> fieldTypes,
                                                 Map<String, String> localTypes) {
        if (expr instanceof ThisExpr) return currentClass;
        if (expr instanceof NameExpr ne) {
            String varName = ne.getNameAsString();
            String type = localTypes.get(varName);
            if (type != null) return type;
            type = fieldTypes.get(varName);
            if (type != null) return type;
            // Could be a class name (static field access)
            return varName;
        }
        if (expr instanceof FieldAccessExpr fae) {
            String objType = resolveExpressionType(fae.getScope(), currentClass, fieldTypes, localTypes);
            if (objType != null) {
                return "?field:" + objType + "." + fae.getNameAsString();
            }
        }
        if (expr instanceof com.github.javaparser.ast.expr.MethodCallExpr mce) {
            // Encode as "?ret:ClassName.methodName" for later return type resolution
            String methodName = mce.getNameAsString();
            if (mce.getScope().isEmpty()) {
                return "?ret:" + currentClass + "." + methodName;
            }
            // For chained method calls: resolve scope type first
            String scopeType = resolveExpressionType(mce.getScope().get(), currentClass, fieldTypes, localTypes);
            if (scopeType != null) {
                return "?ret:" + scopeType + "." + methodName;
            }
        }
        return null;
    }

    /**
     * Extracts reflective call edges based on invoker config.
     * E.g. ejecutarMetodo("calcularImporte", ...) → CallerAdaptadorBean.calcularImporte()
     */
    private List<CallEdge> extractReflectiveEdges(Path file, JavaParser jp,
                                                   List<com.jsrc.app.config.ArchitectureConfig.InvokerDef> invokers) {
        List<CallEdge> edges = new ArrayList<>();
        try {
            String source = Files.readString(file);
            var result = jp.parse(source);
            if (!result.getResult().isPresent()) return edges;

            CompilationUnit cu = result.getResult().get();
            Map<String, com.jsrc.app.config.ArchitectureConfig.InvokerDef> invokerMap = new HashMap<>();
            for (var inv : invokers) {
                invokerMap.put(inv.method(), inv);
            }

            for (ClassOrInterfaceDeclaration cid : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                String callerClass = cid.getNameAsString();
                for (MethodDeclaration md : cid.getMethods()) {
                    for (MethodCallExpr call : md.findAll(MethodCallExpr.class)) {
                        var inv = invokerMap.get(call.getNameAsString());
                        if (inv == null) continue;
                        if (call.getArguments().size() <= inv.targetArg()) continue;
                        var arg = call.getArguments().get(inv.targetArg());
                        if (!(arg instanceof com.github.javaparser.ast.expr.StringLiteralExpr strLit)) continue;

                        String targetMethod = strLit.getValue();
                        // Resolve target class: strip suffixes, append convention
                        String prefix = callerClass;
                        for (String suffix : inv.callerSuffixes()) {
                            if (prefix.endsWith(suffix)) {
                                prefix = prefix.substring(0, prefix.length() - suffix.length());
                                break;
                            }
                        }
                        String convention = inv.resolveClass();
                        String targetClass = prefix + convention.substring(0, 1).toUpperCase() + convention.substring(1);

                        int line = call.getBegin().map(p -> p.line).orElse(-1);
                        edges.add(new CallEdge(callerClass, md.getNameAsString(), targetClass, targetMethod, line));
                    }
                }
            }
        } catch (Exception ex) {
            logger.debug("Error extracting reflective edges from {}: {}", file, ex.getMessage());
        }
        return edges;
    }

    private IndexedClass toIndexedClass(ClassInfo ci, Path file, CodeParser parser,
                                        List<String> fileImports) {
        List<IndexedMethod> methods = ci.methods().stream()
                .map(m -> new IndexedMethod(
                        m.name(), m.signature(), m.startLine(), m.endLine(),
                        m.returnType(),
                        m.annotations().stream().map(a -> a.name()).toList()))
                .toList();

        List<String> annotations = ci.annotations().stream()
                .map(a -> a.name()).toList();

        // Extract field types from the source file using JavaParser
        List<IndexedField> fields = extractFields(file, ci.name());

        return new IndexedClass(
                ci.name(), ci.packageName(), ci.startLine(), ci.endLine(),
                ci.isInterface(), ci.isAbstract(),
                ci.superClass().isEmpty() ? List.of() : List.of(ci.superClass()),
                ci.interfaces(), methods, annotations, fileImports, fields);
    }

    /**
     * Extracts field declarations from a class in the given file.
     */
    private List<IndexedField> extractFields(Path file, String className) {
        List<IndexedField> fields = new ArrayList<>();
        try {
            String source = java.nio.file.Files.readString(file);
            var jp = new JavaParser();
            var result = jp.parse(source);
            if (!result.getResult().isPresent()) return fields;
            for (ClassOrInterfaceDeclaration cid : result.getResult().get()
                    .findAll(ClassOrInterfaceDeclaration.class)) {
                if (!cid.getNameAsString().equals(className)) continue;
                for (com.github.javaparser.ast.body.FieldDeclaration fd : cid.getFields()) {
                    String fieldType = fd.getCommonType().asString();
                    int genIdx = fieldType.indexOf('<');
                    if (genIdx > 0) fieldType = fieldType.substring(0, genIdx);
                    for (com.github.javaparser.ast.body.VariableDeclarator var : fd.getVariables()) {
                        fields.add(new IndexedField(var.getNameAsString(), fieldType));
                    }
                }
            }
        } catch (Exception ex) {
            logger.debug("Error extracting fields from {}: {}", file, ex.getMessage());
        }
        return fields;
    }

    /**
     * Extracts import statements from a Java file.
     */
    private static List<String> extractImports(Path file, JavaParser jp) {
        try {
            String source = Files.readString(file);
            var result = jp.parse(source);
            if (!result.getResult().isPresent()) return List.of();
            return result.getResult().get().getImports().stream()
                    .map(imp -> imp.getNameAsString() + (imp.isAsterisk() ? ".*" : ""))
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    private Map<String, Object> entryToMap(IndexEntry entry) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("path", entry.path());
        map.put("contentHash", entry.contentHash());
        map.put("lastModified", entry.lastModified());
        map.put("classes", entry.classes().stream().map(this::classToMap).toList());
        if (!entry.callEdges().isEmpty()) {
            map.put("callEdges", entry.callEdges().stream().map(this::edgeToMap).toList());
        }
        return map;
    }

    private Map<String, Object> edgeToMap(CallEdge edge) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("callerClass", edge.callerClass());
        map.put("callerMethod", edge.callerMethod());
        if (edge.callerParamCount() >= 0) {
            map.put("callerParamCount", edge.callerParamCount());
        }
        map.put("calleeClass", edge.calleeClass());
        map.put("calleeMethod", edge.calleeMethod());
        map.put("line", edge.line());
        if (edge.argCount() >= 0) {
            map.put("argCount", edge.argCount());
        }
        return map;
    }

    private Map<String, Object> classToMap(IndexedClass ic) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", ic.name());
        map.put("packageName", ic.packageName());
        map.put("qualifiedName", ic.qualifiedName());
        map.put("startLine", ic.startLine());
        map.put("endLine", ic.endLine());
        map.put("isInterface", ic.isInterface());
        map.put("isAbstract", ic.isAbstract());
        map.put("superClass", ic.superClass());
        map.put("interfaces", ic.interfaces());
        map.put("methods", ic.methods().stream().map(this::methodToMap).toList());
        map.put("annotations", ic.annotations());
        if (!ic.imports().isEmpty()) {
            map.put("imports", ic.imports());
        }
        if (!ic.fields().isEmpty()) {
            map.put("fields", ic.fields().stream().map(this::fieldToMap).toList());
        }
        return map;
    }

    private Map<String, Object> fieldToMap(IndexedField f) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", f.name());
        map.put("type", f.type());
        return map;
    }

    private Map<String, Object> methodToMap(IndexedMethod im) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", im.name());
        map.put("signature", im.signature());
        map.put("startLine", im.startLine());
        map.put("endLine", im.endLine());
        map.put("returnType", im.returnType());
        map.put("annotations", im.annotations());
        return map;
    }

}
