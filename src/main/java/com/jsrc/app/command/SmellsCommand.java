package com.jsrc.app.command;

import java.nio.file.Path;

/**
 * Detects code smells in Java source files.
 * <p>
 * When a target is provided (class name or file path), scans only matching files.
 * When target is {@code null}, prints usage help instead of scanning the entire codebase.
 * Use target {@code "--all"} to scan the entire codebase.
 */
public class SmellsCommand implements Command {

    private final String target;

    public SmellsCommand(String target) {
        this.target = target;
    }

    @Override
    public int execute(CommandContext ctx) {
        if (target == null) {
            printUsage();
            return 0;
        }

        if ("--all".equals(target)) {
            return scanAll(ctx);
        }

        return scanTarget(ctx, target);
    }

    private int scanAll(CommandContext ctx) {
        int totalSmells = 0;
        for (Path file : ctx.javaFiles()) {
            var smells = ctx.parser().detectSmells(file);
            totalSmells += smells.size();
            ctx.formatter().printSmells(smells, file);
        }
        return totalSmells;
    }

    private int scanTarget(CommandContext ctx, String target) {
        int totalSmells = 0;
        boolean found = false;

        for (Path file : ctx.javaFiles()) {
            String fileName = file.getFileName().toString();
            String fileNameNoExt = fileName.replace(".java", "");

            if (fileNameNoExt.equals(target)
                    || fileName.equals(target)
                    || file.toString().contains(target)) {
                found = true;
                var smells = ctx.parser().detectSmells(file);
                totalSmells += smells.size();
                ctx.formatter().printSmells(smells, file);
            }
        }

        if (!found) {
            System.err.printf("No files matching '%s' found in codebase%n", target);
        }
        return totalSmells;
    }

    private void printUsage() {
        System.err.println("Usage: jsrc --smells <ClassName|file|--all>");
        System.err.println();
        System.err.println("  <ClassName>   Scan a specific class (e.g. OrderService)");
        System.err.println("  <file>        Scan files matching path (e.g. src/main/Service.java)");
        System.err.println("  --all         Scan the entire codebase");
        System.err.println();
        System.err.println("Examples:");
        System.err.println("  jsrc --smells OrderService");
        System.err.println("  jsrc --smells --all");
        System.err.println("  jsrc --smells --all --json");
    }
}
