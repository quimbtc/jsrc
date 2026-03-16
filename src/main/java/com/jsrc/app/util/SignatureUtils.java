package com.jsrc.app.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared utilities for parsing Java method signatures.
 * Eliminates duplication across CallGraphBuilder, MethodTargetResolver, and SmellsCommand.
 */
public final class SignatureUtils {

    private SignatureUtils() {}

    /**
     * Extracts the parameter portion from a method signature string.
     * E.g. "public void foo(String s, int x)" → "(String, int)"
     *
     * @return "(Type1, Type2)" or "()" if no params or unparseable
     */
    public static String extractParams(String signature) {
        int open = signature.indexOf('(');
        int close = signature.lastIndexOf(')');
        if (open < 0 || close < 0 || close <= open) return "()";
        String inner = signature.substring(open + 1, close).trim();
        if (inner.isEmpty()) return "()";

        List<String> params = splitOutsideGenerics(inner);
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < params.size(); i++) {
            String part = params.get(i).trim();
            // Take type only (first token), skip param name
            String[] tokens = part.split("\\s+");
            sb.append(tokens[0]);
            if (i < params.size() - 1) sb.append(", ");
        }
        sb.append(")");
        return sb.toString();
    }

    /**
     * Counts parameters from a method signature string.
     * E.g. "public void foo(String s, int x)" → 2, "void bar()" → 0.
     * Handles generics correctly (e.g. {@code HashMap<K, V>} counts as one param).
     *
     * @return parameter count, or -1 if unparseable
     */
    public static int countParams(String signature) {
        if (signature == null || signature.isEmpty()) return -1;
        int open = signature.indexOf('(');
        int close = signature.lastIndexOf(')');
        if (open < 0 || close <= open) return -1;
        String inner = signature.substring(open + 1, close).trim();
        if (inner.isEmpty()) return 0;
        int depth = 0;
        int count = 1;
        for (char c : inner.toCharArray()) {
            if (c == '<') depth++;
            else if (c == '>') depth--;
            else if (c == ',' && depth == 0) count++;
        }
        return count;
    }

    /**
     * Splits a parameter string by commas, respecting generic depth.
     * E.g. "HashMap&lt;K, V&gt; m, String s" → ["HashMap&lt;K, V&gt; m", "String s"]
     */
    private static List<String> splitOutsideGenerics(String params) {
        List<String> result = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < params.length(); i++) {
            char c = params.charAt(i);
            if (c == '<') depth++;
            else if (c == '>') depth--;
            else if (c == ',' && depth == 0) {
                result.add(params.substring(start, i));
                start = i + 1;
            }
        }
        result.add(params.substring(start));
        return result;
    }
}
