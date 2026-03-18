package com.jsrc.app.command;

import com.jsrc.app.output.MarkdownFormatter;
import com.jsrc.app.analysis.ContextAssembler;
import com.jsrc.app.parser.model.ClassInfo;

public class ContextCommand implements Command {
    private final String className;
    private final boolean mdOutput;

    public ContextCommand(String className, boolean mdOutput) {
        this.className = className;
        this.mdOutput = mdOutput;
    }

    @Override
    public int execute(CommandContext ctx) {
        var allClasses = ctx.getAllClasses();
        ClassInfo resolved = SummaryCommand.resolveOrExit(allClasses, className);
        if (resolved == null) return 0;

        var arch = ctx.config() != null ? ctx.config().architecture() : null;
        var assembler = new ContextAssembler(ctx.parser(), ctx.dependencyAnalyzer());
        var result = assembler.assemble(
                ctx.javaFiles(), resolved.name(), allClasses, arch, ctx.callGraph());
        if (result == null) return 0;

        var ctxMap = result.toMap();
        if (mdOutput) {
            System.out.println(MarkdownFormatter.toMarkdown(ctxMap));
        } else {
            ctx.formatter().printResult(ctxMap);
        }
        return 1;
    }
}
