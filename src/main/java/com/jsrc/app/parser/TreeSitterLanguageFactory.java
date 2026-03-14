package com.jsrc.app.parser;

import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.SymbolLookup;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.treesitter.jtreesitter.Language;

/**
 * Manages native Tree-sitter language library loading.
 * Caches loaded languages so the native library is extracted and loaded only once per language.
 */
public final class TreeSitterLanguageFactory {

    private static final Logger logger = LoggerFactory.getLogger(TreeSitterLanguageFactory.class);
    private static final Map<String, Language> CACHE = new ConcurrentHashMap<>();
    private static volatile boolean coreLibraryLoaded = false;

    private static final String[] CLASSPATH_SEARCH_PATHS = {
            "native/%s",
            "META-INF/native/%s",
            "META-INF/native/lib/%s",
            "%s"
    };

    private static final String[] SYSTEM_LIB_DIRS = buildSystemLibDirs();

    private static String[] buildSystemLibDirs() {
        String userHome = System.getProperty("user.home", "");
        return new String[] {
                userHome + "/lib",
                "/usr/local/lib",
                "/usr/lib",
                "/lib"
        };
    }

    private TreeSitterLanguageFactory() {}

    /**
     * Returns a cached Tree-sitter {@link Language} for the given language key,
     * loading the native library on first access.
     *
     * @param languageKey lowercase key, e.g. "java"
     * @return loaded Language instance
     * @throws IllegalArgumentException if the language is not supported
     * @throws RuntimeException         if the native library cannot be loaded
     */
    public static Language getLanguage(String languageKey) {
        if (languageKey == null || languageKey.isBlank()) {
            throw new IllegalArgumentException("Language key must not be null or blank");
        }

        String normalizedKey = languageKey.toLowerCase();
        return CACHE.computeIfAbsent(normalizedKey, TreeSitterLanguageFactory::loadLanguage);
    }

    private static Language loadLanguage(String langKey) {
        return switch (langKey) {
            case "java" -> loadNativeLanguage("tree-sitter-java", "tree_sitter_java");
            default -> throw new IllegalArgumentException("Unsupported language: " + langKey);
        };
    }

    private static synchronized void ensureCoreLibraryLoaded() {
        if (coreLibraryLoaded) return;
        String coreLibName = System.mapLibraryName("tree-sitter");
        for (String dir : SYSTEM_LIB_DIRS) {
            Path libPath = Path.of(dir, coreLibName);
            if (Files.exists(libPath)) {
                try {
                    System.load(libPath.toAbsolutePath().toString());
                    coreLibraryLoaded = true;
                    logger.debug("Core tree-sitter library loaded from {}", libPath);
                    return;
                } catch (UnsatisfiedLinkError e) {
                    logger.debug("Failed to load core tree-sitter from {}: {}", libPath, e.getMessage());
                }
            }
        }
        logger.warn("Core tree-sitter library not found in system paths");
    }

    private static Language loadNativeLanguage(String libraryBaseName, String symbolName) {
        ensureCoreLibraryLoaded();

        // Strategy 1: try system-installed library (java.library.path, /usr/local/lib, etc.)
        Language systemLoaded = tryLoadFromSystem(libraryBaseName, symbolName);
        if (systemLoaded != null) return systemLoaded;

        // Strategy 2: try classpath-packaged library
        String libraryFileName = System.mapLibraryName(libraryBaseName);
        URL libraryUrl = findLibraryInClasspath(libraryFileName);
        if (libraryUrl != null) {
            return loadFromClasspath(libraryUrl, libraryBaseName, libraryFileName, symbolName);
        }

        throw new RuntimeException(
                "Native library '%s' not found in system or classpath".formatted(libraryBaseName));
    }

    @SuppressWarnings("null")
    private static Language tryLoadFromSystem(String libraryBaseName, String symbolName) {
        String fileName = System.mapLibraryName(libraryBaseName);

        for (String dir : SYSTEM_LIB_DIRS) {
            Path libPath = Path.of(dir, fileName);
            if (Files.exists(libPath)) {
                try {
                    String absPath = libPath.toAbsolutePath().toString();
                    System.load(absPath);
                    SymbolLookup symbols = SymbolLookup.libraryLookup(absPath, Arena.global());
                    Language language = Language.load(symbols, symbolName);
                    logger.debug("Tree-sitter language '{}' loaded from {}", libraryBaseName, absPath);
                    return language;
                } catch (UnsatisfiedLinkError | RuntimeException e) {
                    logger.debug("Failed to load '{}' from {}: {}", libraryBaseName, dir, e.getMessage());
                }
            }
        }

        logger.debug("System load failed for '{}', trying classpath", libraryBaseName);
        return null;
    }

    @SuppressWarnings("null")
    private static Language loadFromClasspath(URL libraryUrl, String baseName, String fileName, String symbolName) {
        try {
            Path tempLib = extractToTempFile(libraryUrl, baseName, fileName);
            System.load(tempLib.toAbsolutePath().toString());
            SymbolLookup symbols = SymbolLookup.libraryLookup(
                    tempLib.toAbsolutePath().toString(), Arena.global());
            Language language = Language.load(symbols, symbolName);
            logger.debug("Tree-sitter language '{}' loaded from classpath", baseName);
            return language;
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to load native Tree-sitter library '%s' from classpath".formatted(baseName), e);
        }
    }

    private static URL findLibraryInClasspath(String libraryFileName) {
        ClassLoader cl = TreeSitterLanguageFactory.class.getClassLoader();
        for (String pattern : CLASSPATH_SEARCH_PATHS) {
            String path = pattern.formatted(libraryFileName);
            URL url = cl.getResource(path);
            if (url != null) {
                logger.debug("Native library found at classpath: {}", path);
                return url;
            }
        }
        return null;
    }

    private static Path extractToTempFile(URL libraryUrl, String baseName, String fileName) throws Exception {
        String extension = fileName.substring(fileName.lastIndexOf('.'));
        Path tempFile = Files.createTempFile(baseName, extension);
        tempFile.toFile().deleteOnExit();

        try (InputStream is = libraryUrl.openStream()) {
            Files.copy(is, tempFile, StandardCopyOption.REPLACE_EXISTING);
        }
        tempFile.toFile().setExecutable(true);
        return tempFile;
    }
}
