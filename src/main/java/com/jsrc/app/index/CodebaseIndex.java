package com.jsrc.app.index;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
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
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
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
        Map<String, IndexEntry> existingByPath = new LinkedHashMap<>();
        for (IndexEntry e : existing) {
            existingByPath.put(e.path(), e);
        }

        entries.clear();
        int reindexed = 0;

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
                List<IndexedClass> indexed = classes.stream()
                        .map(ci -> toIndexedClass(ci, file, parser))
                        .toList();

                // Extract call edges from the same file
                List<CallEdge> edges = extractCallEdges(file);

                entries.add(new IndexEntry(relativePath, hash, lastModified, indexed, edges));
                reindexed++;
            } catch (IOException ex) {
                logger.error("Error indexing {}: {}", file, ex.getMessage());
            }
        }

        return reindexed;
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
                    callEdges.add(new CallEdge(
                            str(edgeMap, "callerClass"), str(edgeMap, "callerMethod"),
                            str(edgeMap, "calleeClass"), str(edgeMap, "calleeMethod"),
                            intVal(edgeMap, "line")));
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

        return new IndexedClass(name, pkg, startLine, endLine,
                isInterface, isAbstract, superClass, interfaces,
                methods, annotations, imports);
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
     * Each edge represents a method call: caller → callee.
     */
    private List<CallEdge> extractCallEdges(Path file) {
        List<CallEdge> edges = new ArrayList<>();
        try {
            String source = Files.readString(file);
            var jp = new JavaParser();
            var result = jp.parse(source);
            if (!result.getResult().isPresent()) return edges;

            CompilationUnit cu = result.getResult().get();
            for (ClassOrInterfaceDeclaration cid : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                String className = cid.getNameAsString();
                for (MethodDeclaration md : cid.getMethods()) {
                    String methodName = md.getNameAsString();
                    for (MethodCallExpr call : md.findAll(MethodCallExpr.class)) {
                        String calleeMethod = call.getNameAsString();
                        String calleeClass = resolveCalleeClass(call, className);
                        int line = call.getBegin().map(p -> p.line).orElse(-1);
                        edges.add(new CallEdge(className, methodName, calleeClass, calleeMethod, line));
                    }
                }
            }
        } catch (Exception ex) {
            logger.debug("Error extracting call edges from {}: {}", file, ex.getMessage());
        }
        return edges;
    }

    private static String resolveCalleeClass(MethodCallExpr call, String currentClass) {
        if (call.getScope().isEmpty()) return currentClass;
        var scope = call.getScope().get();
        if (scope instanceof ThisExpr) return currentClass;
        if (scope instanceof NameExpr ne) return ne.getNameAsString();
        return "?";
    }

    private IndexedClass toIndexedClass(ClassInfo ci, Path file, CodeParser parser) {
        List<IndexedMethod> methods = ci.methods().stream()
                .map(m -> new IndexedMethod(
                        m.name(), m.signature(), m.startLine(), m.endLine(),
                        m.returnType(),
                        m.annotations().stream().map(a -> a.name()).toList()))
                .toList();

        List<String> annotations = ci.annotations().stream()
                .map(a -> a.name()).toList();

        return new IndexedClass(
                ci.name(), ci.packageName(), ci.startLine(), ci.endLine(),
                ci.isInterface(), ci.isAbstract(),
                ci.superClass().isEmpty() ? List.of() : List.of(ci.superClass()),
                ci.interfaces(), methods, annotations, List.of());
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
        map.put("calleeClass", edge.calleeClass());
        map.put("calleeMethod", edge.calleeMethod());
        map.put("line", edge.line());
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
