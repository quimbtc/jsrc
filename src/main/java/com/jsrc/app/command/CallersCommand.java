package com.jsrc.app.command;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.jsrc.app.architecture.InvokerResolver;
import com.jsrc.app.analysis.CallGraphBuilder;
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

        var targets = resolved.targets();

        List<Map<String, Object>> callers = new ArrayList<>();
        for (var target : targets) {
            for (var call : graphBuilder.getCallersOf(target)) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("className", call.caller().className());
                entry.put("methodName", call.caller().methodName());
                entry.put("qualifiedRef", MethodTargetResolver.qualifiedDisplayName(call.caller(), signatures, packages));
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
                    entry.put("className", rc.callerClass());
                    entry.put("methodName", rc.callerMethod());
                    entry.put("line", rc.line());
                    entry.put("type", "reflective");
                    entry.put("targetClass", rc.targetClass());
                    callers.add(entry);
                }
            }
        }

        ctx.formatter().printRefs(callers, "Callers", methodName);
        return callers.size();
    }
}
