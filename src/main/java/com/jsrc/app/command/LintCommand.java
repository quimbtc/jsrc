package com.jsrc.app.command;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.jsrc.app.analysis.CallGraph;
import com.jsrc.app.parser.model.ClassInfo;
import com.jsrc.app.parser.model.FieldInfo;

/**
 * Pre-compile diagnostics from the index: unknown types, dead code, style inconsistencies.
 * Catches common agent mistakes before running mvn compile.
 */
public class LintCommand implements Command {

    private static final Set<String> JDK_TYPES = Set.of(
            // java.lang
            "String", "Object", "Integer", "Long", "Double", "Float", "Boolean",
            "Byte", "Short", "Character", "Number", "Math", "System", "Thread",
            "Class", "Enum", "Record", "Annotation", "StringBuilder", "StringBuffer",
            "Runnable", "Callable", "Comparable", "Serializable", "Iterable",
            "AutoCloseable", "Cloneable", "Throwable", "ThreadLocal", "InheritableThreadLocal",
            "ProcessBuilder", "Runtime", "ClassLoader", "SecurityManager", "StackTraceElement",
            "Deprecated", "Override", "SuppressWarnings", "FunctionalInterface",
            "Void", "ThreadGroup", "Package", "Module",
            // java.util
            "List", "Map", "Set", "Collection", "Queue", "Deque", "Iterator",
            "Optional", "Stream", "Arrays", "Collections", "Objects",
            "HashMap", "ArrayList", "LinkedList", "HashSet", "TreeMap", "TreeSet",
            "LinkedHashMap", "LinkedHashSet", "Properties", "UUID", "Random",
            "Locale", "Currency", "Date", "Calendar", "TimeZone",
            // java.util.concurrent
            "Future", "CompletableFuture", "Executor", "ExecutorService",
            "ConcurrentHashMap", "CopyOnWriteArrayList", "AtomicInteger", "AtomicLong",
            "CountDownLatch", "Semaphore", "Lock", "ReentrantLock",
            // java.util.function
            "Function", "Supplier", "Consumer", "Predicate", "BiFunction", "BiConsumer",
            // java.io / java.nio
            "Path", "File", "InputStream", "OutputStream", "Reader", "Writer",
            "BufferedReader", "BufferedWriter", "PrintStream", "PrintWriter",
            "IOException", "FileNotFoundException",
            // java.time
            "LocalDate", "LocalTime", "LocalDateTime", "ZonedDateTime", "Instant",
            "Duration", "Period", "ZoneId", "OffsetDateTime",
            // java.math
            "BigDecimal", "BigInteger",
            // java.util.regex
            "Pattern", "Matcher",
            // java.net
            "URL", "URI", "Socket", "HttpURLConnection",
            // java.lang exceptions
            "Exception", "RuntimeException", "IllegalArgumentException",
            "IllegalStateException", "NullPointerException", "UnsupportedOperationException",
            // Logging
            "Logger", "Log", "LoggerFactory",
            // Common Spring types (ubiquitous)
            "ApplicationContext", "BeanFactory", "Environment", "Resource",
            "Assert");

    private static final Set<String> PRIMITIVES = Set.of(
            "int", "long", "short", "byte", "float", "double", "boolean", "char", "void");

    private final String className;

    public LintCommand(String className) {
        this.className = className;
    }

    @Override
    public int execute(CommandContext ctx) {
        var allClasses = ctx.getAllClasses();
        ClassInfo target = allClasses.stream()
                .filter(c -> c.name().equals(className) || c.qualifiedName().equals(className))
                .findFirst().orElse(null);

        if (target == null) {
            ctx.formatter().printResult(Map.of("error", "Class not found: " + className));
            return 0;
        }

        Set<String> knownTypes = new java.util.HashSet<>(JDK_TYPES);
        knownTypes.addAll(PRIMITIVES);
        for (var ci : allClasses) knownTypes.add(ci.name());

        // Resolve types from the class's own imports
        if (ctx.indexed() != null) {
            for (var entry : ctx.indexed().getEntries()) {
                for (var ic : entry.classes()) {
                    if (ic.name().equals(target.name())) {
                        for (String imp : ic.imports()) {
                            // Extract simple name from import: org.springframework.core.io.ResourceLoader → ResourceLoader
                            int lastDot = imp.lastIndexOf('.');
                            if (lastDot >= 0 && !imp.endsWith(".*")) {
                                knownTypes.add(imp.substring(lastDot + 1));
                            }
                        }
                    }
                }
            }
        }

        List<Map<String, Object>> diagnostics = new ArrayList<>();

        // Check field types exist
        for (FieldInfo f : target.fields()) {
            String type = f.type();
            if (!knownTypes.contains(type) && !type.contains(".") && !type.contains("[]")) {
                diagnostics.add(diag("warning", 0,
                        "Unknown type '" + type + "' for field '" + f.name() + "' — not in index or JDK"));
            }
        }

        // Check missing imports (type used in fields/methods but not imported)
        if (ctx.indexed() != null) {
            Set<String> importedTypes = new java.util.HashSet<>();
            String targetPkg = target.packageName();
            for (var entry : ctx.indexed().getEntries()) {
                for (var ic : entry.classes()) {
                    if (ic.name().equals(target.name())) {
                        for (String imp : ic.imports()) {
                            int lastDot = imp.lastIndexOf('.');
                            if (lastDot >= 0) importedTypes.add(imp.substring(lastDot + 1));
                            if (imp.endsWith(".*")) importedTypes.add(imp); // wildcard
                        }
                        // Same-package classes are auto-imported
                        for (var otherEntry : ctx.indexed().getEntries()) {
                            for (var otherCls : otherEntry.classes()) {
                                if (otherCls.packageName().equals(targetPkg)) {
                                    importedTypes.add(otherCls.name());
                                }
                            }
                        }
                    }
                }
            }
            if (!importedTypes.isEmpty()) {
                for (FieldInfo f : target.fields()) {
                    String type = f.type();
                    if (!knownTypes.contains(type) && !importedTypes.contains(type)
                            && !type.contains(".") && !type.contains("[]")
                            && !type.contains("<")) {
                        diagnostics.add(diag("warning", 0,
                                "Type '" + type + "' used in field '" + f.name()
                                        + "' may not be imported"));
                    }
                }
            }
        }

        // Check for dead code (private-like methods with 0 callers)
        CallGraph graph = ctx.callGraph();
        for (var m : target.methods()) {
            if (m.name().equals(target.name())) continue; // skip constructors
            if (m.name().equals("main") || m.name().equals("toString")
                    || m.name().equals("hashCode") || m.name().equals("equals")) continue;

            var refs = graph.findMethodsByName(m.name()).stream()
                    .filter(r -> r.className().equals(target.name()))
                    .toList();
            boolean hasCaller = refs.stream()
                    .anyMatch(r -> !graph.getCallersOf(r).isEmpty());

            // Only report private-ish methods (those not called from outside)
            if (!hasCaller && m.signature() != null && !m.signature().contains("public")) {
                diagnostics.add(diag("info", m.startLine(),
                        "Method " + m.name() + "() has no callers — potential dead code"));
            }
        }

        if (ctx.mdOutput()) {
            var sb = new StringBuilder();
            sb.append("# Lint: `").append(className).append("`\n\n");
            if (diagnostics.isEmpty()) {
                sb.append("✅ No issues found.\n");
            } else {
                sb.append("| Severity | Line | Issue |\n|----------|------|-------|\n");
                for (var d : diagnostics) {
                    String icon = "warning".equals(d.get("severity")) ? "⚠️" : "ℹ️";
                    sb.append("| ").append(icon).append(" ").append(d.get("severity"));
                    sb.append(" | ").append(d.getOrDefault("line", "-"));
                    sb.append(" | ").append(d.get("message")).append(" |\n");
                }
                sb.append("\n**Total: ").append(diagnostics.size()).append(" issue(s)**\n");
            }
            com.jsrc.app.output.MarkdownWriter.output(sb.toString(), ctx.outDir(), "lint-" + className);
            return diagnostics.size();
        }

        // Add summary
        long errors = diagnostics.stream().filter(d -> "error".equals(d.get("severity"))).count();
        long warnings = diagnostics.stream().filter(d -> "warning".equals(d.get("severity"))).count();
        long infos = diagnostics.stream().filter(d -> "info".equals(d.get("severity"))).count();

        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("class", target.qualifiedName());
        result.put("diagnostics", diagnostics);
        result.put("summary", Map.of("errors", errors, "warnings", warnings, "infos", infos,
                "total", diagnostics.size()));
        ctx.formatter().printResult(result);
        return diagnostics.size();
    }

    private Map<String, Object> diag(String severity, int line, String message) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("severity", severity);
        if (line > 0) d.put("line", line);
        d.put("message", message);
        return d;
    }
}
