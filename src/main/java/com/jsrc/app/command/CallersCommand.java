package com.jsrc.app.command;

import java.util.ArrayList;
import java.util.Objects;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.jsrc.app.architecture.InvokerResolver;
import com.jsrc.app.analysis.CallGraph;
import com.jsrc.app.util.MethodResolver;
import com.jsrc.app.util.MethodTargetResolver;

public class CallersCommand implements Command {
    private final String methodInput;

    public CallersCommand(String methodInput) {
        this.methodInput = methodInput;
    }

    @Override
    public int execute(CommandContext ctx) {
        var ref = MethodResolver.parse(methodInput);
        String methodName = ref.methodName();

        CallGraph graph = ctx.callGraph();

        var resolved = MethodTargetResolver.resolve(ref, graph);
        var signatures = MethodTargetResolver.buildSignatureMap(ctx.indexed());
        var packages = MethodTargetResolver.buildClassPackageMap(ctx.indexed());
        var methodPackages = MethodTargetResolver.buildMethodPackageMap(ctx.indexed());

        if (resolved.isAmbiguous()) {
            var candidates = MethodTargetResolver.buildCandidates(resolved.targets(), signatures, packages);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ambiguous", true);
            result.put("method", ref.hasClassName()
                    ? ref.className() + "." + ref.methodName() : ref.methodName());
            result.put("candidates", candidates);
            result.put("message", "Multiple methods found. Use Class.method(Type1,Type2) to disambiguate.");
            ctx.formatter().printResult(result);
            return 0;
        }

        var targets = resolved.targets();

        List<Map<String, Object>> callers = new ArrayList<>();
        for (var target : targets) {
            for (var call : graph.getCallersOf(target)) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("class", ctx.qualify(call.caller().className()));
                entry.put("method", call.caller().methodName());

                entry.put("line", call.line());
                entry.put("type", "direct");
                callers.add(entry);
            }
        }

        // Add reflective callers — skip if index already has them
        if (ctx.config() != null && !ctx.config().architecture().invokers().isEmpty()
                && !(ctx.indexed() != null && ctx.indexed().hasCallEdges())) {
            var resolver = new InvokerResolver(ctx.config().architecture().invokers());
            for (var rc : resolver.resolve(ctx.javaFiles())) {
                if (rc.targetMethod().equals(methodName)) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("class", rc.callerClass());
                    entry.put("method", rc.callerMethod());
                    entry.put("line", rc.line());
                    entry.put("type", "reflective");
                    entry.put("targetClass", rc.targetClass());
                    callers.add(entry);
                }
            }
        }

        if (!ctx.fullOutput() && callers.size() > 0) {
            // Compact: just class.method list, no line numbers or qualified refs
            var compact = new java.util.LinkedHashMap<String, Object>();
            compact.put("method", methodInput);
            compact.put("total", callers.size());
            compact.put("callers", callers.stream()
                    .map(e -> Objects.toString(e.get("class"), "?") + "." + Objects.toString(e.get("method"), "?"))
                    .distinct()
                    .toList());
            ctx.formatter().printResult(compact);
        } else {
            ctx.formatter().printRefs(callers, "Callers", methodName);
        }
        return callers.size();
    }
}
