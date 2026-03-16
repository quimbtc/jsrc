package com.jsrc.app.parser.model;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Uniquely identifies a method within the codebase.
 *
 * @param className      simple class name (e.g. "HybridJavaParser"), or "?" if unresolved
 * @param methodName     method name (e.g. "detectSmells")
 * @param parameterCount number of parameters (to distinguish overloads)
 * @param filePath       source file where the method is declared (null for unresolved references)
 */
public record MethodReference(
        String className,
        String methodName,
        int parameterCount,
        Path filePath
) {

    /**
     * Creates a reference for a method whose declaring class could not be resolved.
     */
    public static MethodReference unresolved(String methodName, int parameterCount) {
        return new MethodReference("?", methodName, parameterCount, null);
    }

    /**
     * Two references match if they have the same class (or either is "?") and same method name.
     * Parameter count is checked only when both are known (>= 0).
     */
    public boolean matches(MethodReference other) {
        if (!methodName.equals(other.methodName)) return false;
        boolean classMatch = "?".equals(className) || "?".equals(other.className)
                || className.equals(other.className);
        if (!classMatch) return false;
        if (parameterCount >= 0 && other.parameterCount >= 0) {
            return parameterCount == other.parameterCount;
        }
        return true;
    }

    /**
     * Compact display: ClassName.methodName()
     * <p>
     * Includes parameter count when known (>= 0) to distinguish overloads.
     * E.g. "Service.process/2()" vs "Service.process/1()".
     */
    public String displayName() {
        if (parameterCount >= 0) {
            return className + "." + methodName + "/" + parameterCount + "()";
        }
        return className + "." + methodName + "()";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MethodReference that)) return false;
        return className.equals(that.className)
                && methodName.equals(that.methodName)
                && parameterCount == that.parameterCount;
    }

    @Override
    public int hashCode() {
        return Objects.hash(className, methodName, parameterCount);
    }

    @Override
    public String toString() {
        return displayName();
    }
}
