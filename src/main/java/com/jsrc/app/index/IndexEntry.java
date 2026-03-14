package com.jsrc.app.index;

import java.util.List;

/**
 * Index entry for a single source file.
 *
 * @param path         relative file path from source root
 * @param contentHash  SHA-256 hash of file content for invalidation
 * @param lastModified file last modified timestamp (epoch millis)
 * @param classes      class metadata indexed from this file
 */
public record IndexEntry(
        String path,
        String contentHash,
        long lastModified,
        List<IndexedClass> classes
) {}
