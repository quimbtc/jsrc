package com.jsrc.app.index;

/**
 * Represents a method call edge in the call graph index.
 *
 * @param callerClass  class containing the calling method
 * @param callerMethod name of the calling method
 * @param calleeClass  class containing the called method (or "?" if unresolved)
 * @param calleeMethod name of the called method
 * @param line         source line of the call
 */
public record CallEdge(
        String callerClass,
        String callerMethod,
        String calleeClass,
        String calleeMethod,
        int line
) {}
