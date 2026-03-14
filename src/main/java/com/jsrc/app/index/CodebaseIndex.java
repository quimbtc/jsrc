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
                String hash = sha256(content);
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

                entries.add(new IndexEntry(relativePath, hash, lastModified, indexed));
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
    public static List<IndexEntry> load(Path projectRoot) {
        Path indexFile = projectRoot.resolve(INDEX_DIR).resolve(INDEX_FILE);
        if (!Files.exists(indexFile)) {
            return List.of();
        }
        // For now, return empty — full JSON parsing would require a parser
        // The incremental build will detect all files as new
        logger.debug("Index file exists at {}, but full load not yet implemented", indexFile);
        return List.of();
    }

    // ---- internal ----

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

    private static String sha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
