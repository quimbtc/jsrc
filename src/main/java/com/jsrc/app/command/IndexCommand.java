package com.jsrc.app.command;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

import com.jsrc.app.ExitCode;
import com.jsrc.app.index.CodebaseIndex;

public class IndexCommand implements Command {
    @Override
    public int execute(CommandContext ctx) {
        Path root = Paths.get(ctx.rootPath());
        System.err.printf("Indexing %d Java files under '%s'...%n", ctx.javaFiles().size(), ctx.rootPath());

        var existing = CodebaseIndex.load(root);
        // Force re-index entries that lack call edges (from older index versions)
        existing = existing.stream()
                .filter(e -> !e.callEdges().isEmpty())
                .toList();
        var index = new CodebaseIndex();
        var invokers = (ctx.config() != null)
                ? ctx.config().architecture().invokers()
                : java.util.List.<com.jsrc.app.config.ArchitectureConfig.InvokerDef>of();
        int reindexed = index.build(ctx.parser(), ctx.javaFiles(), root, existing, invokers);

        try {
            index.save(root);
            System.err.printf("Done. Indexed %d files (%d re-indexed, %d cached).%n",
                    ctx.javaFiles().size(), reindexed, ctx.javaFiles().size() - reindexed);
        } catch (IOException ex) {
            System.err.printf("Error saving index: %s%n", ex.getMessage());
            System.exit(ExitCode.IO_ERROR);
        }
        return 0;
    }
}
