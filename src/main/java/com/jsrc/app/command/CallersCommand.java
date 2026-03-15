package com.jsrc.app.command;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.jsrc.app.architecture.InvokerResolver;
import com.jsrc.app.analysis.CallGraphBuilder;
import com.jsrc.app.util.MethodResolver;

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

        var allTargets = graphBuilder.findMethodsByName(methodName);
        var targets = ref.hasParamTypes()
                ? allTargets.stream().filter(t -> t.parameterCount() == ref.paramTypes().size())
                        .collect(Collectors.toSet())
                : allTargets;

        List<Map<String, Object>> callers = new ArrayList<>();
        for (var target : targets) {
            for (var call : graphBuilder.getCallersOf(target)) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("className", call.caller().className());
                entry.put("methodName", call.caller().methodName());
                entry.put("line", call.line());
                entry.put("type", "direct");
                callers.add(entry);
            }
        }

        // Add reflective callers from invoker config
        if (ctx.config() != null && !ctx.config().architecture().invokers().isEmpty()) {
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
