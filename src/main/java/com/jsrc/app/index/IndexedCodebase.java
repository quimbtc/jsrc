package com.jsrc.app.index;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.jsrc.app.parser.CodeParser;
import com.jsrc.app.parser.HybridJavaParser;
import com.jsrc.app.parser.model.AnnotationInfo;
import com.jsrc.app.parser.model.ClassInfo;
import com.jsrc.app.parser.model.MethodInfo;

/**
 * Provides ClassInfo and MethodInfo from a persisted index,
 * avoiding full source re-parsing. Auto-refreshes stale entries
 * when files have been modified since indexing.
 */
public class IndexedCodebase {

    private static final Logger logger = LoggerFactory.getLogger(IndexedCodebase.class);

    private final List<IndexEntry> entries;
    private List<ClassInfo> allClasses;

    private IndexedCodebase(List<IndexEntry> entries) {
        this.entries = entries;
    }

    /**
     * Tries to load an indexed codebase from disk.
     * If files have changed since indexing, automatically re-parses
     * only the modified/new files and updates the persisted index.
     *
     * @param sourceRoot project root where .jsrc/index.json lives
     * @param currentFiles current list of Java files on disk
     * @return IndexedCodebase if index exists, null otherwise
     */
    public static IndexedCodebase tryLoad(Path sourceRoot, List<Path> currentFiles) {
        List<IndexEntry> existing = CodebaseIndex.load(sourceRoot);
        if (existing.isEmpty()) {
            return null;
        }

        // Build lookup by relative path
        Map<String, IndexEntry> byPath = new HashMap<>();
        for (IndexEntry e : existing) {
            byPath.put(e.path(), e);
        }

        // Detect changes
        List<IndexEntry> refreshed = new ArrayList<>();
        Set<String> currentPaths = new HashSet<>();
        int staleCount = 0;
        CodeParser parser = null; // lazy init only if needed

        for (Path file : currentFiles) {
            String relativePath = sourceRoot.relativize(file).toString();
            currentPaths.add(relativePath);

            IndexEntry prev = byPath.get(relativePath);
            if (prev != null) {
                // Check if file was modified since indexing
                try {
                    long currentModified = Files.getLastModifiedTime(file).toMillis();
                    if (currentModified <= prev.lastModified()) {
                        // Unchanged — reuse cached entry
                        refreshed.add(prev);
                        continue;
                    }
                } catch (IOException e) {
                    // Can't read timestamp — re-parse to be safe
                }
            }

            // New or modified file — re-parse
            if (parser == null) parser = new HybridJavaParser();
            staleCount++;
            try {
                byte[] content = Files.readAllBytes(file);
                String hash = com.jsrc.app.util.Hashing.sha256(content);
                long lastModified = Files.getLastModifiedTime(file).toMillis();
                List<ClassInfo> classes = parser.parseClasses(file);
                List<IndexedClass> indexed = classes.stream()
                        .map(ci -> classInfoToIndexed(ci))
                        .toList();
                refreshed.add(new IndexEntry(relativePath, hash, lastModified, indexed));
            } catch (IOException e) {
                logger.error("Error refreshing {}: {}", file, e.getMessage());
                if (prev != null) refreshed.add(prev); // keep stale rather than lose
            }
        }

        // Detect deleted files (in index but not on disk) — just skip them

        if (staleCount > 0) {
            logger.info("Auto-refreshed {} stale/new file(s), {} cached", staleCount, refreshed.size() - staleCount);
            // Persist updated index
            var updatedIndex = new CodebaseIndex(refreshed);
            try {
                updatedIndex.save(sourceRoot);
            } catch (IOException e) {
                logger.warn("Could not save refreshed index: {}", e.getMessage());
            }
        } else {
            logger.info("Index up-to-date: {} entries", refreshed.size());
        }

        return new IndexedCodebase(refreshed);
    }

    /**
     * Tries to load without auto-refresh (original behavior).
     */
    public static IndexedCodebase tryLoad(Path sourceRoot) {
        List<IndexEntry> entries = CodebaseIndex.load(sourceRoot);
        if (entries.isEmpty()) {
            return null;
        }
        logger.info("Using index with {} file entries", entries.size());
        return new IndexedCodebase(entries);
    }

    /**
     * Returns all classes from the index, converted to ClassInfo.
     */
    public List<ClassInfo> getAllClasses() {
        if (allClasses == null) {
            allClasses = new ArrayList<>();
            for (IndexEntry entry : entries) {
                for (IndexedClass ic : entry.classes()) {
                    allClasses.add(toClassInfo(ic));
                }
            }
        }
        return allClasses;
    }

    /**
     * Returns the file path (relative) for a given class name.
     */
    public String findFileForClass(String className) {
        for (IndexEntry entry : entries) {
            for (IndexedClass ic : entry.classes()) {
                if (ic.name().equals(className) || ic.qualifiedName().equals(className)) {
                    return entry.path();
                }
            }
        }
        return null;
    }

    /**
     * Returns all methods matching a name from the index.
     */
    public List<MethodInfo> findMethodsByName(String methodName) {
        List<MethodInfo> results = new ArrayList<>();
        for (IndexEntry entry : entries) {
            for (IndexedClass ic : entry.classes()) {
                for (IndexedMethod im : ic.methods()) {
                    if (im.name().equals(methodName)) {
                        results.add(toMethodInfo(im, ic.name()));
                    }
                }
            }
        }
        return results;
    }

    /**
     * Returns all methods with a given annotation from the index.
     */
    public List<MethodInfo> findMethodsByAnnotation(String annotationName) {
        List<MethodInfo> results = new ArrayList<>();
        for (IndexEntry entry : entries) {
            for (IndexedClass ic : entry.classes()) {
                for (IndexedMethod im : ic.methods()) {
                    if (im.annotations().contains(annotationName)) {
                        results.add(toMethodInfo(im, ic.name()));
                    }
                }
            }
        }
        return results;
    }

    /**
     * Returns all classes with a given annotation from the index.
     */
    public List<ClassInfo> findClassesByAnnotation(String annotationName) {
        return getAllClasses().stream()
                .filter(ci -> ci.annotations().stream()
                        .anyMatch(a -> a.name().equals(annotationName)))
                .toList();
    }

    /**
     * Returns the number of indexed files.
     */
    public int fileCount() {
        return entries.size();
    }

    // ---- conversion ----

    private static ClassInfo toClassInfo(IndexedClass ic) {
        List<MethodInfo> methods = ic.methods().stream()
                .map(im -> toMethodInfo(im, ic.name()))
                .toList();

        List<AnnotationInfo> annotations = ic.annotations().stream()
                .map(AnnotationInfo::marker)
                .toList();

        String superClass = ic.superClass().isEmpty() ? "" : ic.superClass().getFirst();

        return new ClassInfo(
                ic.name(), ic.packageName(), ic.startLine(), ic.endLine(),
                List.of(), methods, superClass,
                ic.interfaces(), annotations, ic.isInterface());
    }

    private static MethodInfo toMethodInfo(IndexedMethod im, String className) {
        List<AnnotationInfo> annotations = im.annotations().stream()
                .map(AnnotationInfo::marker)
                .toList();

        return new MethodInfo(
                im.name(), className, im.startLine(), im.endLine(),
                im.returnType(), List.of(), List.of(),
                "", // no content from index
                annotations, List.of(), List.of(), null);
    }

    private static IndexedClass classInfoToIndexed(ClassInfo ci) {
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

}
