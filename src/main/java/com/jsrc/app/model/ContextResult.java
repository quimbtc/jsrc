package com.jsrc.app.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.jsrc.app.parser.model.ClassInfo;
import com.jsrc.app.parser.model.CodeSmell;

/**
 * Typed result of a context assembly for a class.
 * Replaces the untyped Map&lt;String, Object&gt; from ContextAssembler.
 */
public record ContextResult(
        ClassInfo target,
        DependencyResult dependencies,
        HierarchyInfo hierarchy,
        String layer,
        List<MethodCallInfo> callGraph,
        List<CodeSmell> smells,
        String source,
        String file
) {
    /**
     * Hierarchy information for the context result.
     */
    public record HierarchyInfo(
            String superClass,
            List<String> interfaces,
            List<String> subClasses,
            List<String> implementors
    ) {}

    /**
     * Call graph info for a single method.
     */
    public record MethodCallInfo(
            String methodName,
            List<String> callers,
            List<String> callees
    ) {}

    /**
     * Converts to a Map for JSON/Markdown formatters (backward compatible output).
     */
    public Map<String, Object> toMap() {
        Map<String, Object> ctx = new LinkedHashMap<>();

        // Class summary
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("name", target.name());
        summary.put("packageName", target.packageName());
        summary.put("qualifiedName", target.qualifiedName());
        summary.put("isInterface", target.isInterface());
        summary.put("isAbstract", target.isAbstract());
        summary.put("startLine", target.startLine());
        summary.put("endLine", target.endLine());
        if (!target.superClass().isEmpty()) summary.put("superClass", target.superClass());
        if (!target.interfaces().isEmpty()) summary.put("interfaces", target.interfaces());
        if (!target.annotations().isEmpty()) {
            summary.put("annotations", target.annotations().stream()
                    .map(a -> a.toString()).toList());
        }
        ctx.put("class", summary);

        // Methods
        ctx.put("methods", target.methods().stream().map(m -> {
            Map<String, Object> mm = new LinkedHashMap<>();
            mm.put("name", m.name());
            mm.put("signature", m.signature());
            mm.put("returnType", m.returnType());
            mm.put("startLine", m.startLine());
            mm.put("endLine", m.endLine());
            if (!m.annotations().isEmpty()) {
                mm.put("annotations", m.annotations().stream().map(a -> a.toString()).toList());
            }
            if (!m.thrownExceptions().isEmpty()) mm.put("throws", m.thrownExceptions());
            return mm;
        }).toList());

        // Dependencies
        if (dependencies != null) {
            Map<String, Object> depsMap = new LinkedHashMap<>();
            depsMap.put("imports", dependencies.imports());
            depsMap.put("fields", dependencies.fieldDependencies().stream()
                    .map(f -> f.type() + " " + f.name()).toList());
            depsMap.put("constructorParams", dependencies.constructorDependencies().stream()
                    .map(f -> f.type() + " " + f.name()).toList());
            ctx.put("dependencies", depsMap);
        }

        // Hierarchy
        if (hierarchy != null) {
            Map<String, Object> h = new LinkedHashMap<>();
            if (!hierarchy.superClass().isEmpty()) h.put("superClass", hierarchy.superClass());
            if (!hierarchy.interfaces().isEmpty()) h.put("interfaces", hierarchy.interfaces());
            if (!hierarchy.subClasses().isEmpty()) h.put("subClasses", hierarchy.subClasses());
            if (!hierarchy.implementors().isEmpty()) h.put("implementors", hierarchy.implementors());
            if (!h.isEmpty()) ctx.put("hierarchy", h);
        }

        if (layer != null) ctx.put("layer", layer);

        // Call graph
        if (!callGraph.isEmpty()) {
            ctx.put("callGraph", callGraph.stream().map(mc -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("method", mc.methodName());
                if (!mc.callers().isEmpty()) m.put("callers", mc.callers());
                if (!mc.callees().isEmpty()) m.put("callees", mc.callees());
                return m;
            }).toList());
        }

        // Smells
        if (!smells.isEmpty()) {
            ctx.put("smells", smells.stream().map(s -> {
                Map<String, Object> sm = new LinkedHashMap<>();
                sm.put("ruleId", s.ruleId());
                sm.put("severity", s.severity().name());
                sm.put("message", s.message());
                sm.put("line", s.line());
                return sm;
            }).toList());
        }

        if (source != null) ctx.put("source", source);
        if (file != null) ctx.put("file", file);

        return ctx;
    }
}
