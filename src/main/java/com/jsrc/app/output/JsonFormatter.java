package com.jsrc.app.output;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.jsrc.app.parser.model.AnnotationInfo;
import com.jsrc.app.parser.model.CallChain;
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

    @Override
    public void printMethods(List<MethodInfo> methods, Path file, String methodName) {
        List<Map<String, Object>> items = methods.stream()
                .map(m -> methodToMap(m, file))
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
    public void printCallChains(List<CallChain> chains, String methodName) {
        List<Map<String, Object>> items = chains.stream()
                .map(this::chainToMap)
                .toList();
        System.out.println(JsonWriter.toJson(items));
    }

    // ---- serialization helpers ----

    private Map<String, Object> methodToMap(MethodInfo m, Path file) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", m.name());
        map.put("className", m.className());
        map.put("file", file.toString());
        map.put("startLine", m.startLine());
        map.put("endLine", m.endLine());
        map.put("returnType", m.returnType());
        map.put("modifiers", m.modifiers());
        map.put("parameters", m.parameters().stream().map(this::paramToMap).toList());
        map.put("signature", m.signature());

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
