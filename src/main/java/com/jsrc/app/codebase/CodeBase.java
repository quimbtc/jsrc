package com.jsrc.app.codebase;

import java.nio.file.Path;
import java.util.List;

/**
 * Represents a project codebase rooted at a specific directory.
 */
public interface CodeBase {

    /**
     * Returns the root path of this codebase.
     */
    Path getRoot();

    /**
     * Returns all source files in this codebase.
     * Results are cached after the first call.
     */
    List<Path> getFiles();
}
