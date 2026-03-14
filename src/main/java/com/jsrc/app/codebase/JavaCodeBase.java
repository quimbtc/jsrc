package com.jsrc.app.codebase;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link CodeBase} implementation for Java projects.
 * Lazily discovers and caches .java files under the configured root.
 */
public class JavaCodeBase implements CodeBase {

    private static final Logger logger = LoggerFactory.getLogger(JavaCodeBase.class);
    private static final String JAVA_EXTENSION = "java";

    private final Path root;
    private final CodeBaseLoader loader;
    private List<Path> cachedFiles;

    public JavaCodeBase(Path root, CodeBaseLoader loader) {
        if (root == null) {
            throw new IllegalArgumentException("Root path must not be null");
        }
        if (loader == null) {
            throw new IllegalArgumentException("Loader must not be null");
        }
        this.root = root;
        this.loader = loader;
        logger.debug("JavaCodeBase initialized at root: {}", root);
    }

    public JavaCodeBase(String rootPath, CodeBaseLoader loader) {
        this(Paths.get(rootPath), loader);
    }

    @Override
    public Path getRoot() {
        return root;
    }

    @Override
    public List<Path> getFiles() {
        if (cachedFiles == null) {
            logger.debug("Loading Java files from: {}", root);
            cachedFiles = loader.loadFilesFrom(root.toString(), JAVA_EXTENSION);
            logger.info("Loaded {} Java files", cachedFiles.size());
        }
        return cachedFiles;
    }
}
