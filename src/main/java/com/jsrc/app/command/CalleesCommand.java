package com.jsrc.app.command;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.jsrc.app.analysis.CallGraph;
import com.jsrc.app.util.MethodResolver;
import com.jsrc.app.util.MethodTargetResolver;

public class CalleesCommand implements Command {
    private final String methodInput;

    public CalleesCommand(String methodInput) {
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
            result.put("methodName", ref.hasClassName()
                    ? ref.className() + "." + ref.methodName() : ref.methodName());
            result.put("candidates", candidates);
            result.put("message", "Multiple methods found. Use Class.method(Type1,Type2) to disambiguate.");
            ctx.formatter().printResult(result);
            return 0;
        }

        var sources = resolved.targets();

        List<Map<String, Object>> callees = new ArrayList<>();
        for (var source : sources) {
            for (var call : graph.getCalleesOf(source)) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("className", ctx.qualify(call.callee().className()));
                entry.put("methodName", call.callee().methodName());
                entry.put("qualifiedRef", MethodTargetResolver.qualifiedDisplayName(call.callee(), signatures, packages, methodPackages));
                entry.put("line", call.line());
                entry.put("type", "direct");
                callees.add(entry);
            }
        }

        if (!ctx.fullOutput() && callees.size() > 0) {
            // Compact: just class.method list, no line numbers or qualified refs
            var compact = new java.util.LinkedHashMap<String, Object>();
            compact.put("method", methodInput);
            compact.put("total", callees.size());
            compact.put("callees", callees.stream()
                    .map(e -> e.get("className") + "." + e.get("methodName"))
                    .distinct()
                    .toList());
            ctx.formatter().printResult(compact);
        } else {
            ctx.formatter().printRefs(callees, "Callees", methodName);
        }
        return callees.size();
    }
}
