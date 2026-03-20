package com.jsrc.app.command;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.jsrc.app.analysis.CallGraph;
import com.jsrc.app.parser.model.ClassInfo;
import com.jsrc.app.util.MethodResolver;

/**
 * Finds tests related to a method/class with confidence levels.
 * Heuristics: ClassTest naming, call graph, imports.
 */
public class TestForCommand implements Command {

    private final String methodInput;

    public TestForCommand(String methodInput) {
        this.methodInput = methodInput;
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
            // Try to find unique class with this method
            var matches = graph.findMethodsByName(methodName);
            if (matches.size() == 1) {
                className = matches.iterator().next().className();
            }
        }

        // Find test classes
        List<Map<String, Object>> tests = new ArrayList<>();
        Set<String> seen = new java.util.LinkedHashSet<>();

        // HIGH: ClassTest/ClassTests naming convention
        if (className != null) {
            String cn = className;
            allClasses.stream()
                    .filter(ci -> ci.name().equals(cn + "Test") || ci.name().equals(cn + "Tests")
                            || ci.name().equals(cn + "IT"))
                    .forEach(ci -> {
                        if (seen.add(ci.qualifiedName())) {
                            // Check if test has method matching target
                            var testMethods = ci.methods().stream()
                                    .filter(m -> m.name().toLowerCase().contains(methodName.toLowerCase()))
                                    .map(m -> m.name())
                                    .toList();
                            tests.add(testEntry(ci.qualifiedName(),
                                    testMethods.isEmpty() ? null : testMethods,
                                    "high", "ClassTest naming convention"));
                        }
                    });
        }

        // HIGH: Test method directly calls target (via call graph)
        if (className != null) {
            String cn = className;
            for (var mref : graph.findMethodsByName(methodName)) {
                if (!mref.className().equals(cn)) continue;
                for (var call : graph.getCallersOf(mref)) {
                    String callerClass = call.caller().className();
                    if (isTestClass(callerClass, allClasses)) {
                        String qualified = ctx.qualify(callerClass);
                        if (seen.add(qualified)) {
                            tests.add(testEntry(qualified, null, "high", "direct call in test"));
                        }
                    }
                }
            }
        }

        // MEDIUM: Test class imports target class
        if (className != null && ctx.indexed() != null) {
            String cn = className;
            for (var ci : allClasses) {
                if (!isTestClass(ci.name(), allClasses)) continue;
                var deps = ctx.indexed().getDependencies(ci.name());
                if (deps.isEmpty()) continue;
                boolean imports = deps.get().imports().stream()
                        .anyMatch(imp -> imp.endsWith("." + cn) || imp.equals(cn));
                if (imports && seen.add(ci.qualifiedName())) {
                    tests.add(testEntry(ci.qualifiedName(), null, "medium", "imports target class"));
                }
            }
        }

        // Sort by confidence
        tests.sort(Comparator.comparingInt(t -> switch ((String) t.get("confidence")) {
            case "high" -> 0;
            case "medium" -> 1;
            default -> 2;
        }));

        // Build suggested command
        List<String> highMedium = tests.stream()
                .filter(t -> !"low".equals(t.get("confidence")))
                .map(t -> ((String) t.get("class")).substring(((String) t.get("class")).lastIndexOf('.') + 1))
                .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("target", methodInput);
        result.put("tests", tests);
        if (!highMedium.isEmpty()) {
            result.put("suggestedCommand", "mvn test -Dtest=" + String.join(",", highMedium));
        }

        ctx.formatter().printResult(result);
        return tests.size();
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
                                           String confidence, String reason) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("class", className);
        if (methods != null && !methods.isEmpty()) entry.put("methods", methods);
        entry.put("confidence", confidence);
        entry.put("reason", reason);
        return entry;
    }
}
