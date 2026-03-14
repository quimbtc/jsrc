package com.jsrc.app.architecture;

/**
 * A rule violation found during architecture check.
 *
 * @param ruleId    the rule that was violated
 * @param className the class that violates the rule
 * @param message   human-readable description
 * @param file      source file path (may be empty if from index)
 * @param line      line number (0 if unknown)
 */
public record Violation(
        String ruleId,
        String className,
        String message,
        String file,
        int line
) {}
