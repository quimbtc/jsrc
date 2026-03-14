package com.jsrc.app.output;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.jsrc.app.parser.model.AnnotationInfo;
import com.jsrc.app.parser.model.CallChain;
import com.jsrc.app.parser.model.ClassInfo;
import com.jsrc.app.parser.model.CodeSmell;
import com.jsrc.app.parser.model.MethodCall;
import com.jsrc.app.parser.model.MethodInfo;
import com.jsrc.app.parser.model.MethodInfo.ParameterInfo;
import com.jsrc.app.parser.model.MethodReference;

/**
 * JSON formatter for CLI output, optimized for agent/LLM consumption.
 * Produces compact JSON to minimize token usage.
 */
public class JsonFormatter implements OutputFormatter {

    private final boolean signatureOnly;
    private final java.util.Set<String> fields;

    public JsonFormatter() {
        this(false, null);
    }

    public JsonFormatter(boolean signatureOnly) {
        this(signatureOnly, null);
    }

    public JsonFormatter(boolean signatureOnly, java.util.Set<String> fields) {
        this.signatureOnly = signatureOnly;
        this.fields = fields;
    }

    @Override
    public void printMethods(List<MethodInfo> methods, Path file, String methodName) {
        List<Map<String, Object>> items = methods.stream()
                .map(m -> FieldsFilter.filter(methodToMap(m, file), fields))
                .toList();
        System.out.println(JsonWriter.toJson(items));
    }

    @Override
    public void printSmells(List<CodeSmell> smells, Path file) {
        List<Map<String, Object>> findings = smells.stream()
                .map(this::smellToMap)
                .toList();

        long errors = smells.stream().filter(s -> s.severity() == CodeSmell.Severity.ERROR).count();
        long warnings = smells.stream().filter(s -> s.severity() == CodeSmell.Severity.WARNING).count();
        long infos = smells.stream().filter(s -> s.severity() == CodeSmell.Severity.INFO).count();

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", smells.size());
        summary.put("errors", errors);
        summary.put("warnings", warnings);
        summary.put("infos", infos);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("file", file.toString());
        result.put("findings", findings);
        result.put("summary", summary);

        System.out.println(JsonWriter.toJson(result));
    }

    @Override
    public void printRefs(List<Map<String, Object>> refs, String label, String target) {
        System.out.println(JsonWriter.toJson(refs));
    }

    @Override
    public void printReadResult(com.jsrc.app.parser.SourceReader.ReadResult result) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("className", result.className());
        if (result.methodName() != null) {
            map.put("methodName", result.methodName());
        }
        map.put("file", result.file().toString());
        map.put("startLine", result.startLine());
        map.put("endLine", result.endLine());
        map.put("content", result.content());
        System.out.println(JsonWriter.toJson(FieldsFilter.filter(map, fields)));
    }

    @Override
    public void printOverview(OverviewResult result) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("totalFiles", result.totalFiles());
        map.put("totalClasses", result.totalClasses());
        map.put("totalInterfaces", result.totalInterfaces());
        map.put("totalMethods", result.totalMethods());
        map.put("totalPackages", result.packages().size());
        map.put("packages", result.packages());
        System.out.println(JsonWriter.toJson(map));
    }

    @Override
    public void printDependencies(DependencyResult result) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("className", result.className());
        map.put("imports", result.imports());
        map.put("fieldDependencies", result.fieldDependencies().stream()
                .map(d -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("type", d.type());
                    m.put("name", d.name());
                    return m;
                }).toList());
        map.put("constructorDependencies", result.constructorDependencies().stream()
                .map(d -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("type", d.type());
                    m.put("name", d.name());
                    return m;
                }).toList());
        System.out.println(JsonWriter.toJson(map));
    }

    @Override
    public void printHierarchy(HierarchyResult result) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("target", result.target());
        map.put("superClass", result.superClass());
        map.put("interfaces", result.interfaces());
        map.put("subClasses", result.subClasses());
        map.put("implementors", result.implementors());
        System.out.println(JsonWriter.toJson(map));
    }

    @Override
    public void printAnnotationMatches(List<AnnotationMatch> matches) {
        List<Map<String, Object>> items = matches.stream()
                .map(m -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("type", m.type());
                    map.put("name", m.name());
                    map.put("className", m.className());
                    map.put("file", m.file().toString());
                    map.put("line", m.line());
                    map.put("annotation", annotationToMap(m.annotation()));
                    return map;
                }).toList();
        System.out.println(JsonWriter.toJson(items));
    }

    @Override
    public void printClassSummary(ClassInfo ci, Path file) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", ci.name());
        map.put("packageName", ci.packageName());
        map.put("qualifiedName", ci.qualifiedName());
        map.put("file", file.toString());
        map.put("startLine", ci.startLine());
        map.put("endLine", ci.endLine());
        map.put("modifiers", ci.modifiers());
        map.put("isInterface", ci.isInterface());
        map.put("isAbstract", ci.isAbstract());
        if (!ci.superClass().isEmpty()) {
            map.put("superClass", ci.superClass());
        }
        if (!ci.interfaces().isEmpty()) {
            map.put("interfaces", ci.interfaces());
        }
        if (!ci.annotations().isEmpty()) {
            map.put("annotations", ci.annotations().stream()
                    .map(this::annotationToMap).toList());
        }
        // Methods as compact signatures (no bodies)
        List<Map<String, Object>> methods = ci.methods().stream()
                .map(m -> {
                    Map<String, Object> mmap = new LinkedHashMap<>();
                    mmap.put("name", m.name());
                    mmap.put("signature", m.signature());
                    mmap.put("startLine", m.startLine());
                    mmap.put("endLine", m.endLine());
                    mmap.put("returnType", m.returnType());
                    return mmap;
                }).toList();
        map.put("methods", methods);

        System.out.println(JsonWriter.toJson(map));
    }

    @Override
    public void printClasses(List<ClassInfo> classes, Path sourceRoot) {
        List<Map<String, Object>> items = classes.stream()
                .map(ci -> FieldsFilter.filter(classToCompactMap(ci), fields))
                .toList();
        System.out.println(JsonWriter.toJson(items));
    }

    @Override
    public void printCallChains(List<CallChain> chains, String methodName) {
        List<Map<String, Object>> items = chains.stream()
                .map(this::chainToMap)
                .toList();
        System.out.println(JsonWriter.toJson(items));
    }

    // ---- serialization helpers ----

    private Map<String, Object> classToCompactMap(ClassInfo ci) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", ci.name());
        map.put("packageName", ci.packageName());
        map.put("qualifiedName", ci.qualifiedName());
        map.put("startLine", ci.startLine());
        map.put("endLine", ci.endLine());
        map.put("modifiers", ci.modifiers());
        map.put("isInterface", ci.isInterface());
        map.put("isAbstract", ci.isAbstract());
        map.put("methodCount", ci.methods().size());
        if (!ci.superClass().isEmpty()) {
            map.put("superClass", ci.superClass());
        }
        if (!ci.interfaces().isEmpty()) {
            map.put("interfaces", ci.interfaces());
        }
        if (!ci.annotations().isEmpty()) {
            map.put("annotations", ci.annotations().stream()
                    .map(this::annotationToMap).toList());
        }
        return map;
    }

    private Map<String, Object> methodToMap(MethodInfo m, Path file) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", m.name());
        map.put("className", m.className());
        map.put("file", file.toString());
        map.put("startLine", m.startLine());
        map.put("endLine", m.endLine());
        map.put("signature", m.signature());

        if (!signatureOnly) {
            map.put("returnType", m.returnType());
            map.put("modifiers", m.modifiers());
            map.put("parameters", m.parameters().stream().map(this::paramToMap).toList());

            if (!m.annotations().isEmpty()) {
                map.put("annotations", m.annotations().stream()
                        .map(this::annotationToMap).toList());
            }
            if (!m.thrownExceptions().isEmpty()) {
                map.put("thrownExceptions", m.thrownExceptions());
            }
            if (!m.typeParameters().isEmpty()) {
                map.put("typeParameters", m.typeParameters());
            }
        }
        // content intentionally omitted to save tokens
        return map;
    }

    private Map<String, Object> paramToMap(ParameterInfo p) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", p.type());
        map.put("name", p.name());
        return map;
    }

    private Map<String, Object> annotationToMap(AnnotationInfo a) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", a.name());
        if (!a.isMarker()) {
            map.put("attributes", a.attributes());
        }
        return map;
    }

    private Map<String, Object> smellToMap(CodeSmell s) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("ruleId", s.ruleId());
        map.put("severity", s.severity().name());
        map.put("message", s.message());
        map.put("line", s.line());
        map.put("methodName", s.methodName());
        map.put("className", s.className());
        return map;
    }

    private Map<String, Object> chainToMap(CallChain chain) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("summary", chain.summary());
        map.put("depth", chain.depth());
        map.put("root", refToMap(chain.root()));
        map.put("target", refToMap(chain.target()));
        map.put("steps", chain.steps().stream().map(this::stepToMap).toList());
        return map;
    }

    private Map<String, Object> stepToMap(MethodCall step) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("caller", refToMap(step.caller()));
        map.put("callee", refToMap(step.callee()));
        map.put("line", step.line());
        return map;
    }

    private Map<String, Object> refToMap(MethodReference ref) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("className", ref.className());
        map.put("methodName", ref.methodName());
        return map;
    }
}
