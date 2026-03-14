package com.jsrc.app.parser.model;

/**
 * Represents a concrete method invocation found in source code.
 *
 * @param caller the method that contains the call
 * @param callee the method being called
 * @param line   1-based line number of the invocation
 */
public record MethodCall(
        MethodReference caller,
        MethodReference callee,
        int line
) {
    @Override
    public String toString() {
        return caller.displayName() + " -> " + callee.displayName() + " [line " + line + "]";
    }
}
