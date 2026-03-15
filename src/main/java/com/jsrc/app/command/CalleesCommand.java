package com.jsrc.app.command;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.jsrc.app.analysis.CallGraphBuilder;
import com.jsrc.app.util.MethodResolver;

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

        var allSources = graphBuilder.findMethodsByName(methodName);
        var sources = ref.hasParamTypes()
                ? allSources.stream().filter(t -> t.parameterCount() == ref.paramTypes().size())
                        .collect(Collectors.toSet())
                : allSources;

        List<Map<String, Object>> callees = new ArrayList<>();
        for (var source : sources) {
            for (var call : graphBuilder.getCalleesOf(source)) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("className", call.callee().className());
                entry.put("methodName", call.callee().methodName());
                entry.put("line", call.line());
                entry.put("type", "direct");
                callees.add(entry);
            }
        }

        ctx.formatter().printRefs(callees, "Callees", methodName);
        return callees.size();
    }
}
