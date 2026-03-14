package com.jsrc.app.util;

import java.util.regex.Pattern;

/**
 * Validates CLI inputs against common agent hallucination patterns.
 * Rejects path traversals, control characters, and invalid identifiers.
 */
public final class InputValidator {

    private static final Pattern JAVA_IDENTIFIER = Pattern.compile("^[a-zA-Z_$][a-zA-Z0-9_$]*(\\.[a-zA-Z_$][a-zA-Z0-9_$]*)*$");
    private static final Pattern PATH_TRAVERSAL = Pattern.compile("(^|[/\\\\])\\.\\.");
    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]");
    private static final Pattern EMBEDDED_QUERY = Pattern.compile("[?#%]");

    private InputValidator() {}

    /**
     * Validates a Java identifier (class name, method name, annotation name).
     * Allows dotted names like "com.app.Service".
     *
     * @return error message, or null if valid
     */
    public static String validateIdentifier(String value, String label) {
        if (value == null || value.isBlank()) {
            return label + " must not be empty";
        }
        if (hasControlChars(value)) {
            return label + " contains control characters";
        }
        if (!JAVA_IDENTIFIER.matcher(value).matches()) {
            return label + " is not a valid Java identifier: " + value;
        }
        return null;
    }

    /**
     * Validates a file system path.
     *
     * @return error message, or null if valid
     */
    public static String validatePath(String value, String label) {
        if (value == null || value.isBlank()) {
            return label + " must not be empty";
        }
        if (hasControlChars(value)) {
            return label + " contains control characters";
        }
        if (value.contains("\0")) {
            return label + " contains null byte";
        }
        if (PATH_TRAVERSAL.matcher(value).find()) {
            return label + " contains path traversal (..)";
        }
        return null;
    }

    /**
     * Validates a command name against known commands.
     *
     * @return error message with suggestion, or null if valid
     */
    public static String validateCommand(String command) {
        if (command == null || command.isBlank()) {
            return "Command must not be empty";
        }
        if (command.startsWith("--")) {
            String[] known = com.jsrc.app.CommandRegistry.knownCommandNames();
            for (String k : known) {
                if (k.equals(command)) return null;
            }
            // Unknown flag-style command — suggest closest
            String closest = findClosest(command, known);
            return "Unknown command: " + command
                    + (closest != null ? ". Did you mean " + closest + "?" : "");
        }
        // Non-flag commands are method names — may include params like process(int)
        String methodPart = command.contains("(") ? command.substring(0, command.indexOf('(')) : command;
        return validateIdentifier(methodPart, "Method name");
    }

    /**
     * Validates a method reference (may contain parens and dots).
     * E.g. "process", "process(int)", "Service.process(int,String)"
     *
     * @return error message, or null if valid
     */
    public static String validateMethodRef(String value, String label) {
        if (value == null || value.isBlank()) {
            return label + " must not be empty";
        }
        if (hasControlChars(value)) {
            return label + " contains control characters";
        }
        // Extract just the identifier parts for validation
        String clean = value.replaceAll("\\(.*\\)", ""); // remove params
        String[] parts = clean.split("\\.");
        for (String part : parts) {
            if (!part.isEmpty() && !JAVA_IDENTIFIER.matcher(part).matches()) {
                return label + " contains invalid identifier: " + part;
            }
        }
        return null;
    }

    private static boolean hasControlChars(String value) {
        return CONTROL_CHARS.matcher(value).find();
    }

    private static String findClosest(String input, String[] candidates) {
        String best = null;
        int bestDist = Integer.MAX_VALUE;
        for (String c : candidates) {
            int dist = levenshtein(input, c);
            if (dist < bestDist && dist <= 3) {
                bestDist = dist;
                best = c;
            }
        }
        return best;
    }

    private static int levenshtein(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) dp[i][0] = i;
        for (int j = 0; j <= b.length(); j++) dp[0][j] = j;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                        dp[i - 1][j - 1] + cost);
            }
        }
        return dp[a.length()][b.length()];
    }
}
