package com.jsrc.app.index;

import java.util.List;

/**
 * Compact method metadata stored in the index.
 *
 * @param name        method name
 * @param signature   full signature string
 * @param startLine   start line
 * @param endLine     end line
 * @param returnType  return type
 * @param annotations annotation names on this method
 */
public record IndexedMethod(
        String name,
        String signature,
        int startLine,
        int endLine,
        String returnType,
        List<String> annotations
) {}
