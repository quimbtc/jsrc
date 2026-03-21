package com.jsrc.app.command;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.jsrc.app.analysis.CallGraph;
import com.jsrc.app.parser.model.ClassInfo;
import com.jsrc.app.parser.model.MethodReference;
import com.jsrc.app.util.MethodResolver;

/**
 * Finds tests related to a method/class with confidence levels.
 * Supports transitive call graph analysis with configurable depth.
 * <p>
 * Heuristics: ClassTest naming, call graph (direct + transitive), imports.
 * Depth controls how many hops up the call graph to traverse:
 * <ul>
 *   <li>0 — direct callers only (no transitive)</li>
 *   <li>1 — one hop (default)</li>
 *   <li>N — N hops</li>
 *   <li>Integer.MAX_VALUE — unlimited ("full")</li>
 * </ul>
 */
public class TestForCommand implements Command {

    private static final int DEFAULT_DEPTH = 1;

    private final String methodInput;
    private final int maxDepth;

    public TestForCommand(String methodInput) {
        this(methodInput, DEFAULT_DEPTH);
    }

    public TestForCommand(String methodInput, int maxDepth) {
        this.methodInput = methodInput;
        this.maxDepth = maxDepth;
    }

    @Override
    public int execute(CommandContext ctx) {
        var ref = MethodResolver.parse(methodInput);
        String className = ref.hasClassName() ? ref.className() : null;
        String methodName = ref.methodName();

        var allClasses = ctx.getAllClasses();
        CallGraph graph = ctx.callGraph();

        // Resolve class name
        if (className == null) {
            var matches = graph.findMethodsByName(methodName);
            if (matches.size() == 1) {
                className = matches.iterator().next().className();
            }
        }

        // Pre-build set of classes that have @Test methods (O(n) scan, done once)
        Set<String> classesWithTestAnnotation = new java.util.HashSet<>();
        for (var ci : allClasses) {
            if (ci.methods().stream().anyMatch(m -> m.annotations().stream()
                    .anyMatch(a -> a.name().equals("Test")))) {
                classesWithTestAnnotation.add(ci.name());
            }
        }

        // Cache test class names: name heuristic OR has @Test methods
        Set<String> testClassNames = new LinkedHashSet<>();
        for (var ci : allClasses) {
            String name = ci.name();
            if (name.endsWith("Test") || name.endsWith("Tests") || name.endsWith("IT")
                    || classesWithTestAnnotation.contains(name)) {
                testClassNames.add(name);
            }
        }

        List<Map<String, Object>> tests = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        // HIGH: ClassTest/ClassTests naming convention (depth-independent)
        if (className != null) {
            String cn = className;
            allClasses.stream()
                    .filter(ci -> ci.name().equals(cn + "Test") || ci.name().equals(cn + "Tests")
                            || ci.name().equals(cn + "IT"))
                    .forEach(ci -> {
                        if (seen.add(ci.qualifiedName())) {
                            var testMethods = ci.methods().stream()
                                    .filter(m -> m.name().toLowerCase().contains(methodName.toLowerCase()))
                                    .map(m -> m.name())
                                    .toList();
                            tests.add(testEntry(ci.qualifiedName(),
                                    testMethods.isEmpty() ? null : testMethods,
                                    "high", "ClassTest naming convention", 0));
                        }
                    });
        }

        // BFS: traverse call graph upward from target method
        if (className != null) {
            String cn = className;
            Set<MethodReference> targetMethods = new LinkedHashSet<>();
            for (var mref : graph.findMethodsByName(methodName)) {
                if (mref.className().equals(cn)) {
                    targetMethods.add(mref);
                }
            }
            findTransitiveTestCallers(graph, testClassNames, targetMethods, tests, seen, ctx);
        }

        // MEDIUM: Test class imports target class (depth-independent, fallback)
        if (className != null && ctx.indexed() != null) {
            String cn = className;
            for (var ci : allClasses) {
                if (!testClassNames.contains(ci.name())) continue;
                var deps = ctx.indexed().getDependencies(ci.name());
                if (deps.isEmpty()) continue;
                boolean imports = deps.get().imports().stream()
                        .anyMatch(imp -> imp.endsWith("." + cn) || imp.equals(cn));
                if (imports && seen.add(ci.qualifiedName())) {
                    tests.add(testEntry(ci.qualifiedName(), null, "medium",
                            "imports target class", 0));
                }
            }
        }

        // Sort: high first, then medium, then low; within same confidence, by depth
        tests.sort(Comparator.<Map<String, Object>, Integer>comparing(
                t -> confidenceOrder((String) t.get("confidence")))
                .thenComparing(t -> t.containsKey("depth")
                        ? ((Number) t.get("depth")).intValue() : 0));

        // Build suggested command
        List<String> highMedium = tests.stream()
                .filter(t -> !"low".equals(t.get("confidence")))
                .map(t -> ((String) t.get("class"))
                        .substring(((String) t.get("class")).lastIndexOf('.') + 1))
                .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("target", methodInput);
        result.put("depth", maxDepth == Integer.MAX_VALUE ? "full" : maxDepth);
        result.put("tests", tests);
        if (!highMedium.isEmpty()) {
            result.put("suggestedCommand", "mvn test -Dtest=" + String.join(",", highMedium));
        }

        ctx.formatter().printResult(result);
        return tests.size();
    }

    /**
     * BFS upward through the call graph, finding test classes at each depth level.
     */
    private void findTransitiveTestCallers(CallGraph graph, Set<String> testClassNames,
                                            Set<MethodReference> seeds,
                                            List<Map<String, Object>> tests,
                                            Set<String> seen, CommandContext ctx) {
        Set<MethodReference> visited = new LinkedHashSet<>(seeds);
        Deque<MethodReference> currentLevel = new ArrayDeque<>(seeds);
        int depth = 0;

        while (!currentLevel.isEmpty() && depth <= maxDepth) {
            Deque<MethodReference> nextLevel = new ArrayDeque<>();

            for (var method : currentLevel) {
                for (var call : graph.getCallersOf(method)) {
                    var caller = call.caller();
                    String callerClass = caller.className();

                    if (testClassNames.contains(callerClass)) {
                        String qualified = ctx.qualify(callerClass);
                        if (seen.add(qualified)) {
                            String confidence = depthToConfidence(depth);
                            String reason = depth == 0
                                    ? "direct call in test"
                                    : "transitive call (depth " + depth + ")";
                            tests.add(testEntry(qualified, null, confidence, reason, depth));
                        }
                    }

                    // Queue non-test callers for next level traversal
                    if (!testClassNames.contains(callerClass) && visited.add(caller)) {
                        nextLevel.add(caller);
                    }
                }
            }

            currentLevel = nextLevel;
            depth++;
        }
    }

    private static String depthToConfidence(int depth) {
        if (depth == 0) return "high";
        if (depth == 1) return "medium";
        return "low";
    }

    private static int confidenceOrder(String confidence) {
        return switch (confidence) {
            case "high" -> 0;
            case "medium" -> 1;
            default -> 2;
        };
    }

    private boolean isTestClass(String name, List<ClassInfo> allClasses) {
        if (name.endsWith("Test") || name.endsWith("Tests") || name.endsWith("IT")) return true;
        return allClasses.stream()
                .filter(ci -> ci.name().equals(name))
                .anyMatch(ci -> ci.methods().stream()
                        .anyMatch(m -> m.annotations().stream()
                                .anyMatch(a -> a.name().equals("Test"))));
    }

    private Map<String, Object> testEntry(String className, List<String> methods,
                                           String confidence, String reason, int depth) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("class", className);
        if (methods != null && !methods.isEmpty()) entry.put("methods", methods);
        entry.put("confidence", confidence);
        entry.put("reason", reason);
        entry.put("depth", depth);
        return entry;
    }
}
