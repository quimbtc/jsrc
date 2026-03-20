package com.jsrc.app.output;

import java.util.List;
import java.util.Map;

/**
 * Formats a context map as a structured Markdown spec draft.
 * Designed for readability in Obsidian, PRs, and wikis.
 * <p>
 * Call graphs are truncated to top-5 per method to avoid wall-of-text.
 * Method signatures shown instead of "(N params)" display names.
 */
public final class MarkdownFormatter {

    private static final int MAX_CALLS = 5;
    private static final int MAX_CALLERS = 10;

    private MarkdownFormatter() {}

    @SuppressWarnings("unchecked")
    public static String toMarkdown(Map<String, Object> ctx) {
        StringBuilder md = new StringBuilder();

        // Header
        Map<String, Object> cls = (Map<String, Object>) ctx.get("class");
        String name = (String) cls.get("name");
        String pkg = (String) cls.getOrDefault("packageName", "");
        boolean isInterface = Boolean.TRUE.equals(cls.get("isInterface"));

        md.append("# ").append(name).append("\n\n");
        if (!pkg.isEmpty()) md.append("**Package:** `").append(pkg).append("`  \n");

        // Layer
        if (ctx.containsKey("layer")) {
            md.append("**Layer:** ").append(ctx.get("layer")).append("  \n");
        }

        // Hierarchy
        if (cls.containsKey("superClass") && !cls.get("superClass").toString().isEmpty()) {
            md.append("**Extends:** `").append(cls.get("superClass")).append("`  \n");
        }
        if (cls.containsKey("interfaces")) {
            List<String> ifaces = (List<String>) cls.get("interfaces");
            if (!ifaces.isEmpty()) {
                md.append("**Implements:** ").append(formatCodeList(ifaces)).append("  \n");
            }
        }
        if (cls.containsKey("annotations")) {
            List<String> anns = (List<String>) cls.get("annotations");
            if (!anns.isEmpty()) {
                md.append("**Annotations:** ").append(String.join(", ", anns)).append("  \n");
            }
        }

        md.append("\n## Description\n\n");
        md.append("<!-- TODO: Describe the purpose and responsibilities of this ")
          .append(isInterface ? "interface" : "class").append(" -->\n\n");

        // Methods — use ### headers for SpecParser compatibility + compact info
        List<Map<String, Object>> methods = (List<Map<String, Object>>) ctx.get("methods");
        if (methods != null && !methods.isEmpty()) {
            md.append("## Methods (").append(methods.size()).append(")\n\n");
            for (Map<String, Object> m : methods) {
                String sig = (String) m.getOrDefault("signature", m.get("name") + "()");
                int start = m.get("startLine") instanceof Number n ? n.intValue() : 0;
                int end = m.get("endLine") instanceof Number n ? n.intValue() : 0;

                StringBuilder methodLine = new StringBuilder(sig);
                if (m.containsKey("throws")) {
                    @SuppressWarnings("unchecked")
                    List<String> thrw = (List<String>) m.get("throws");
                    if (!thrw.isEmpty()) methodLine.append(" throws ").append(String.join(", ", thrw));
                }
                md.append("- `").append(methodLine).append("`\n");
            }
        }

        // Call graph — compact, deduplicated by method name
        List<Map<String, Object>> callGraph = (List<Map<String, Object>>) ctx.get("callGraph");
        if (callGraph != null && !callGraph.isEmpty()) {
            md.append("## Call Graph\n\n");
            java.util.Set<String> seenMethods = new java.util.HashSet<>();
            for (Map<String, Object> cg : callGraph) {
                String method = (String) cg.get("method");
                if (!seenMethods.add(method)) continue; // skip duplicate overloads
                md.append("### ").append(method).append("()\n\n");

                List<String> callees = (List<String>) cg.get("callees");
                if (callees != null && !callees.isEmpty()) {
                    md.append("**Calls:**\n");
                    appendTruncatedList(md, callees, MAX_CALLS);
                    md.append("\n");
                }

                List<String> callers = (List<String>) cg.get("callers");
                if (callers != null && !callers.isEmpty()) {
                    md.append("**Called by:**\n");
                    appendTruncatedList(md, callers, MAX_CALLERS);
                    md.append("\n");
                }
            }
        }

        // Dependencies
        Map<String, Object> deps = (Map<String, Object>) ctx.get("dependencies");
        if (deps != null) {
            md.append("## Dependencies\n\n");
            List<String> ctorParams = (List<String>) deps.get("constructorParams");
            List<String> fields = (List<String>) deps.get("fields");

            if (ctorParams != null && !ctorParams.isEmpty()) {
                md.append("**Constructor injection:**\n");
                for (String p : ctorParams) md.append("- `").append(p).append("`\n");
                md.append("\n");
            }
            if (fields != null && !fields.isEmpty()) {
                md.append("**Fields:**\n");
                for (String f : fields) md.append("- `").append(f).append("`\n");
                md.append("\n");
            }
        }

        // Smells
        List<Map<String, Object>> smells = (List<Map<String, Object>>) ctx.get("smells");
        if (smells != null && !smells.isEmpty()) {
            md.append("## Known Issues\n\n");
            md.append("| Severity | Line | Issue |\n|----------|------|-------|\n");
            for (Map<String, Object> s : smells) {
                String icon = "WARNING".equals(s.get("severity").toString()) ? "⚠️" : "ℹ️";
                md.append("| ").append(icon).append(" ").append(s.get("severity"));
                md.append(" | ").append(s.get("line"));
                md.append(" | ").append(s.get("message")).append(" |\n");
            }
            md.append("\n");
        }

        md.append("## Invariants\n\n");
        md.append("<!-- TODO: Define business rules and invariants -->\n\n");

        return md.toString();
    }

    private static void appendTruncatedList(StringBuilder md, List<String> items, int max) {
        int shown = 0;
        for (String item : items) {
            if (shown >= max) {
                md.append("- ... and ").append(items.size() - max).append(" more\n");
                break;
            }
            // Clean up display: "Bean.get(3 params)" → "Bean.get()"
            md.append("- `").append(cleanDisplayName(item)).append("`\n");
            shown++;
        }
    }

    /**
     * Cleans up a call graph display name:
     * "PropertySourceOrigin.PropertySourceOrigin(3 params)" → "PropertySourceOrigin()"
     * "Binder.bind(4 params)" → "Binder.bind()"
     */
    private static String cleanDisplayName(String name) {
        if (name == null) return "?";
        // Remove "(N params)" pattern
        return name.replaceAll("\\(\\d+ params?\\)", "()");
    }

    private static String formatCodeList(List<String> items) {
        return items.stream()
                .map(i -> "`" + i + "`")
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
    }

    private static String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
