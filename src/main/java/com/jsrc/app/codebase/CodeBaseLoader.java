package com.jsrc.app.codebase;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stateless utility for discovering source files in a directory tree.
 * Each call returns a fresh list; no internal state is accumulated.
 */
public class CodeBaseLoader {

    private static final Logger logger = LoggerFactory.getLogger(CodeBaseLoader.class);

    /**
     * Walks the directory tree under {@code rootPath} and returns all files
     * matching the given extension.
     *
     * @param rootPath  root directory to scan
     * @param extension file extension without the dot (e.g. "java")
     * @return list of matching paths, or empty list if none found or path is invalid
     */
    public List<Path> loadFilesFrom(String rootPath, String extension) {
        Path startPath = Paths.get(rootPath);
        if (!Files.exists(startPath) || !Files.isDirectory(startPath)) {
            logger.warn("Path does not exist or is not a directory: {}", rootPath);
            return Collections.emptyList();
        }

        String suffix = "." + extension;

        try (Stream<Path> walk = Files.walk(startPath)) {
            List<Path> files = walk
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(suffix))
                    .toList();
            logger.debug("Found {} .{} files under {}", files.size(), extension, rootPath);
            return files;
        } catch (IOException e) {
            logger.error("Error walking directory tree at {}: {}", rootPath, e.getMessage(), e);
            return Collections.emptyList();
        }
    }
}
