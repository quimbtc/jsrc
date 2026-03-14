package com.jsrc.app.parser.model;

import java.nio.file.Path;
import java.util.List;

/**
 * Wraps the results of a parsing/search operation on a single file.
 *
 * @param filePath    the file that was parsed
 * @param methods     methods matching the search criteria
 * @param totalParsed total methods found in the file (before filtering)
 */
public record ParseResult(
        Path filePath,
        List<MethodInfo> methods,
        int totalParsed
) {
    public boolean hasResults() {
        return methods != null && !methods.isEmpty();
    }

    public int matchCount() {
        return methods == null ? 0 : methods.size();
    }
}
