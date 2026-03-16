package com.jsrc.app.command;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.jsrc.app.analysis.CallGraphBuilder;
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

        CallGraphBuilder graphBuilder = new CallGraphBuilder();
        if (ctx.indexed() != null && ctx.indexed().hasCallEdges()) {
            graphBuilder.loadFromIndex(ctx.indexed().getEntries());
        } else {
            graphBuilder.build(ctx.javaFiles());
        }

        var resolved = MethodTargetResolver.resolve(ref, graphBuilder);
        var signatures = MethodTargetResolver.buildSignatureMap(ctx.indexed());
        var packages = MethodTargetResolver.buildClassPackageMap(ctx.indexed());

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
            for (var call : graphBuilder.getCalleesOf(source)) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("className", call.callee().className());
                entry.put("methodName", call.callee().methodName());
                entry.put("qualifiedRef", MethodTargetResolver.qualifiedDisplayName(call.callee(), signatures, packages));
                entry.put("line", call.line());
                entry.put("type", "direct");
                callees.add(entry);
            }
        }

        ctx.formatter().printRefs(callees, "Callees", methodName);
        return callees.size();
    }
}
