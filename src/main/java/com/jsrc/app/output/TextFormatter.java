package com.jsrc.app.output;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.jsrc.app.model.AnnotationMatch;
import com.jsrc.app.model.DependencyResult;
import com.jsrc.app.model.HierarchyResult;
import com.jsrc.app.model.OverviewResult;
import com.jsrc.app.parser.model.CallChain;
import com.jsrc.app.parser.model.ClassInfo;
import com.jsrc.app.parser.model.CodeSmell;
import com.jsrc.app.parser.model.MethodCall;
import com.jsrc.app.parser.model.MethodInfo;

/**
 * Human-readable text formatter for CLI output.
 * Preserves the original output format from App.java.
 */
public class TextFormatter implements OutputFormatter {

    private final boolean signatureOnly;

    public TextFormatter() {
        this(false);
    }

    public TextFormatter(boolean signatureOnly) {
        this.signatureOnly = signatureOnly;
    }

    @Override
    public void printMethods(List<MethodInfo> methods, Path file, String methodName) {
        for (MethodInfo m : methods) {
            if (signatureOnly) {
                System.out.printf("%s:%d  %s%n", file.getFileName(), m.startLine(), m.signature());
                continue;
            }

            System.out.printf("%n[%s] %s:%d-%d%n",
                    m.className().isEmpty() ? file.getFileName() : m.className(),
                    file, m.startLine(), m.endLine());
            System.out.printf("  %s%n", m.signature());

            if (!m.annotations().isEmpty()) {
                System.out.printf("  Annotations: %s%n", m.annotations());
            }
            if (!m.thrownExceptions().isEmpty()) {
                System.out.printf("  Throws: %s%n", String.join(", ", m.thrownExceptions()));
            }
            if (!m.typeParameters().isEmpty()) {
                System.out.printf("  Type params: %s%n", String.join(", ", m.typeParameters()));
            }
            if (m.javadoc() != null) {
                String firstLine = m.javadoc().lines().findFirst().orElse("").trim();
                if (firstLine.startsWith("*")) firstLine = firstLine.substring(1).trim();
                System.out.printf("  Javadoc: %s%n", firstLine);
            }
        }
    }

    @Override
    public void printSmells(List<CodeSmell> smells, Path file) {
        if (smells.isEmpty()) return;

        System.out.printf("%n--- %s ---%n", file);
        for (CodeSmell smell : smells) {
            System.out.printf("  [%s] %s at line %d in %s%n    %s%n",
                    smell.severity(), smell.ruleId(), smell.line(),
                    smell.methodName().isEmpty() ? smell.className() : smell.methodName() + "()",
                    smell.message());
        }
    }

    @Override
    public void printViolations(List<com.jsrc.app.architecture.Violation> violations) {
        if (violations.isEmpty()) {
            System.out.println("No violations found.");
            return;
        }
        System.out.printf("Violations (%d):%n", violations.size());
        for (var v : violations) {
            System.out.printf("  [%s] %s: %s%n", v.ruleId(), v.className(), v.message());
        }
    }

    @Override
    public void printDiff(List<String> modified, List<String> added, List<String> deleted) {
        if (modified.isEmpty() && added.isEmpty() && deleted.isEmpty()) {
            System.out.println("No changes since last index.");
            return;
        }
        if (!modified.isEmpty()) {
            System.out.printf("Modified (%d):%n", modified.size());
            modified.forEach(f -> System.out.printf("  M %s%n", f));
        }
        if (!added.isEmpty()) {
            System.out.printf("Added (%d):%n", added.size());
            added.forEach(f -> System.out.printf("  A %s%n", f));
        }
        if (!deleted.isEmpty()) {
            System.out.printf("Deleted (%d):%n", deleted.size());
            deleted.forEach(f -> System.out.printf("  D %s%n", f));
        }
    }

    @Override
    public void printRefs(List<Map<String, Object>> refs, String label, String target) {
        if (refs.isEmpty()) {
            System.out.printf("No %s found for '%s'.%n", label, target);
            return;
        }
        System.out.printf("%s of '%s' (%d):%n", label, target, refs.size());
        for (Map<String, Object> ref : refs) {
            System.out.printf("  %s.%s() [line %s]%n",
                    ref.get("className"), ref.get("methodName"), ref.get("line"));
        }
    }

    @Override
    public void printReadResult(com.jsrc.app.parser.SourceReader.ReadResult result) {
        String label = result.methodName() != null
                ? result.className() + "." + result.methodName() + "()"
                : result.className();
        System.out.printf("// %s  %s:%d-%d%n", label, result.file(), result.startLine(), result.endLine());
        System.out.println(result.content());
    }

    @Override
    public void printOverview(OverviewResult result) {
        System.out.printf("Codebase Overview%n");
        System.out.printf("  Files:      %d%n", result.totalFiles());
        System.out.printf("  Classes:    %d%n", result.totalClasses());
        System.out.printf("  Interfaces: %d%n", result.totalInterfaces());
        System.out.printf("  Methods:    %d%n", result.totalMethods());
        System.out.printf("  Packages:   %d%n", result.packages().size());
        if (!result.packages().isEmpty()) {
            System.out.printf("  Package tree:%n");
            for (String pkg : result.packages()) {
                System.out.printf("    %s%n", pkg);
            }
        }
    }

    @Override
    public void printDependencies(DependencyResult result) {
        System.out.printf("Dependencies for: %s%n", result.className());
        if (!result.imports().isEmpty()) {
            System.out.printf("  Imports (%d):%n", result.imports().size());
            for (String imp : result.imports()) {
                System.out.printf("    %s%n", imp);
            }
        }
        if (!result.fieldDependencies().isEmpty()) {
            System.out.printf("  Fields:%n");
            for (var dep : result.fieldDependencies()) {
                System.out.printf("    %s %s%n", dep.type(), dep.name());
            }
        }
        if (!result.constructorDependencies().isEmpty()) {
            System.out.printf("  Constructor params:%n");
            for (var dep : result.constructorDependencies()) {
                System.out.printf("    %s %s%n", dep.type(), dep.name());
            }
        }
    }

    @Override
    public void printHierarchy(HierarchyResult result) {
        System.out.printf("Hierarchy for: %s%n", result.target());
        if (!result.superClass().isEmpty()) {
            System.out.printf("  Extends: %s%n", result.superClass());
        }
        if (!result.interfaces().isEmpty()) {
            System.out.printf("  Implements: %s%n", String.join(", ", result.interfaces()));
        }
        if (!result.subClasses().isEmpty()) {
            System.out.printf("  Subclasses: %s%n", String.join(", ", result.subClasses()));
        }
        if (!result.implementors().isEmpty()) {
            System.out.printf("  Implementors: %s%n", String.join(", ", result.implementors()));
        }
    }

    @Override
    public void printAnnotationMatches(List<AnnotationMatch> matches) {
        if (matches.isEmpty()) {
            System.out.println("No matches found.");
            return;
        }
        for (AnnotationMatch m : matches) {
            System.out.printf("  [%s] %s in %s (%s:%d)  %s%n",
                    m.type(), m.name(), m.className(),
                    m.file().getFileName(), m.line(), m.annotation());
        }
        System.out.printf("%nTotal: %d match(es).%n", matches.size());
    }

    @Override
    public void printClassSummary(ClassInfo ci, Path file) {
        String kind = ci.isInterface() ? "interface" : ci.isAbstract() ? "abstract class" : "class";
        System.out.printf("%s %s%n", kind, ci.qualifiedName());
        System.out.printf("  File: %s  lines %d-%d%n", file, ci.startLine(), ci.endLine());

        if (!ci.superClass().isEmpty()) {
            System.out.printf("  Extends: %s%n", ci.superClass());
        }
        if (!ci.interfaces().isEmpty()) {
            System.out.printf("  Implements: %s%n", String.join(", ", ci.interfaces()));
        }
        if (!ci.annotations().isEmpty()) {
            System.out.printf("  Annotations: %s%n", ci.annotations());
        }

        System.out.printf("  Methods (%d):%n", ci.methods().size());
        for (MethodInfo m : ci.methods()) {
            System.out.printf("    %s:%d  %s%n", file.getFileName(), m.startLine(), m.signature());
        }
    }

    @Override
    public void printClasses(List<ClassInfo> classes, Path sourceRoot) {
        if (classes.isEmpty()) {
            System.out.println("No classes found.");
            return;
        }
        for (ClassInfo ci : classes) {
            String kind = ci.isInterface() ? "interface" : ci.isAbstract() ? "abstract class" : "class";
            System.out.printf("  %s %s (%s) [%d methods] lines %d-%d%n",
                    kind, ci.qualifiedName(),
                    String.join(" ", ci.modifiers()),
                    ci.methods().size(),
                    ci.startLine(), ci.endLine());
        }
        System.out.printf("%nTotal: %d type(s).%n", classes.size());
    }

    @Override
    public void printCallChains(List<CallChain> chains, String methodName) {
        printCallChains(chains, methodName, java.util.Map.of());
    }

    @Override
    public void printCallChains(List<CallChain> chains, String methodName,
                                 java.util.Map<String, String> signatures) {
        if (chains.isEmpty()) {
            System.out.printf("No call chains found for method '%s'.%n", methodName);
            return;
        }

        System.out.printf("Found %d call chain(s):%n", chains.size());
        for (int i = 0; i < chains.size(); i++) {
            CallChain chain = chains.get(i);
            System.out.printf("%n  Chain %d (depth %d):%n", i + 1, chain.depth());
            System.out.printf("    %s%n", chainSummary(chain, signatures));
            for (MethodCall step : chain.steps()) {
                System.out.printf("    %s -> %s [line %d]%n",
                        displayWithParams(step.caller(), signatures),
                        displayWithParams(step.callee(), signatures),
                        step.line());
            }
        }
    }

    private static String displayWithParams(com.jsrc.app.parser.model.MethodReference ref,
                                             java.util.Map<String, String> signatures) {
        String key = ref.className() + "." + ref.methodName();
        // Try keyed by param count first (for overload disambiguation)
        String params = null;
        if (ref.parameterCount() >= 0) {
            params = signatures.get(key + "/" + ref.parameterCount());
        }
        if (params == null) {
            params = signatures.getOrDefault(key, "()");
        }
        return ref.className() + "." + ref.methodName() + params;
    }

    private static String chainSummary(CallChain chain, java.util.Map<String, String> signatures) {
        StringBuilder sb = new StringBuilder();
        sb.append(displayWithParams(chain.root(), signatures));
        for (MethodCall step : chain.steps()) {
            sb.append(" -> ").append(displayWithParams(step.callee(), signatures));
        }
        return sb.toString();
    }

    @Override
    public void printResult(Object data) {
        // Text mode: print JSON as-is (no fields filtering in text mode)
        System.out.println(JsonWriter.toJson(data));
    }
}
