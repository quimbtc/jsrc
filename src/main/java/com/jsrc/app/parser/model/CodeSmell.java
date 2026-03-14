package com.jsrc.app.parser.model;

/**
 * Represents a code smell or potential issue detected in source code.
 *
 * @param ruleId     unique rule identifier (e.g. "SWITCH_WITHOUT_DEFAULT")
 * @param severity   how critical this finding is
 * @param message    human-readable description of the issue
 * @param line       1-based line number where the issue occurs
 * @param methodName enclosing method name (empty if class-level)
 * @param className  enclosing class name
 */
public record CodeSmell(
        String ruleId,
        Severity severity,
        String message,
        int line,
        String methodName,
        String className
) {
    public enum Severity { INFO, WARNING, ERROR }

    @Override
    public String toString() {
        String location = className.isEmpty() ? "" : className;
        if (!methodName.isEmpty()) {
            location += "." + methodName + "()";
        }
        return String.format("[%s] %s at line %d in %s: %s",
                severity, ruleId, line, location, message);
    }
}
