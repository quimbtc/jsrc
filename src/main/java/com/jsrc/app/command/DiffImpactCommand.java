package com.jsrc.app.command;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import com.jsrc.app.analysis.CallGraph;
import com.jsrc.app.parser.model.MethodCall;
import com.jsrc.app.parser.model.MethodReference;

/**
 * Analyzes git changes and computes consolidated impact.
 * Combines --changed + --impact into one command.
 * <p>
 * Usage: jsrc --diff-impact [ref] (default: HEAD)
 */
public class DiffImpactCommand implements Command {

    private static final int MAX_DEPTH = 30;
    private final String ref;

    public DiffImpactCommand(String ref) {
        this.ref = ref != null ? ref : "HEAD";
    }

    @Override
    public int execute(CommandContext ctx) {
        // 1. Get changed files from git
        List<String> changedFiles;
        try {
            changedFiles = getChangedFiles(ctx.rootPath());
        } catch (Exception e) {
            ctx.formatter().printResult(Map.of("error", "Failed to run git diff: " + e.getMessage()));
            return 0;
        }

        if (changedFiles.isEmpty()) {
            ctx.formatter().printResult(Map.of("ref", ref, "changedFiles", 0, "message", "No Java files changed"));
            return 0;
        }

        // 2. Map changed files → classes → methods
        CallGraph graph = ctx.callGraph();
        List<Map<String, Object>> details = new ArrayList<>();
        Set<String> allAffected = new LinkedHashSet<>();
        Set<String> suggestedTests = new LinkedHashSet<>();
        int totalDirectCallers = 0;

        for (String file : changedFiles) {
            // Find classes in this file
            String simpleFile = file.contains("/") ? file.substring(file.lastIndexOf('/') + 1) : file;
            String className = simpleFile.replace(".java", "");

            // Get methods of this class from index
            if (ctx.indexed() == null) continue;
            var classInfo = ctx.getAllClasses().stream()
                    .filter(ci -> ci.name().equals(className))
                    .findFirst().orElse(null);
            if (classInfo == null) continue;

            String qualifiedName = ctx.qualify(className);
            List<String> changedMethods = new ArrayList<>();

            // All methods in the changed file are potentially changed
            for (var m : classInfo.methods()) {
                changedMethods.add(m.name());

                // Find callers
                for (var ref : graph.findMethodsByName(m.name())) {
                    if (!ref.className().equals(className)) continue;
                    for (var call : graph.getCallersOf(ref)) {
                        String caller = ctx.qualify(call.caller().className());
                        if (!caller.equals(qualifiedName)) {
                            allAffected.add(caller);
                            totalDirectCallers++;
                        }
                        // Test heuristic: caller class name ends with Test/Tests
                        if (call.caller().className().endsWith("Test") || call.caller().className().endsWith("Tests")) {
                            suggestedTests.add(call.caller().className());
                        }
                    }
                }
            }

            // Test heuristic: ClassTest naming
            String testName = className + "Test";
            ctx.getAllClasses().stream()
                    .filter(ci -> ci.name().equals(testName) || ci.name().equals(className + "Tests"))
                    .forEach(ci -> suggestedTests.add(ci.name()));

            // BFS for transitive callers
            Set<MethodReference> visited = new HashSet<>();
            Queue<MethodReference> queue = new LinkedList<>();
            for (var m : classInfo.methods()) {
                for (var mref : graph.findMethodsByName(m.name())) {
                    if (!mref.className().equals(className)) continue;
                    for (var call : graph.getCallersOf(mref)) {
                        if (visited.add(call.caller())) queue.add(call.caller());
                    }
                }
            }
            int depth = 0;
            while (!queue.isEmpty() && depth < MAX_DEPTH) {
                int size = queue.size();
                for (int i = 0; i < size; i++) {
                    MethodReference current = queue.poll();
                    if (!"?".equals(current.className())) {
                        allAffected.add(ctx.qualify(current.className()));
                    }
                    for (var call : graph.getCallersOf(current)) {
                        if (visited.add(call.caller())) queue.add(call.caller());
                    }
                }
                depth++;
            }

            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("class", qualifiedName);
            detail.put("file", file);
            detail.put("changedMethods", changedMethods.stream().distinct().toList());
            details.add(detail);
        }

        // Risk level
        String risk;
        if (totalDirectCallers == 0) risk = "none";
        else if (totalDirectCallers <= 5) risk = "low";
        else if (totalDirectCallers <= 20) risk = "medium";
        else risk = "high";

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ref", ref);
        result.put("changedFiles", changedFiles.size());
        result.put("changedClasses", details.size());
        result.put("impactedClasses", allAffected.size());
        result.put("riskLevel", risk);
        result.put("details", details);
        result.put("affectedClasses", allAffected.stream().sorted().toList());
        if (!suggestedTests.isEmpty()) {
            result.put("suggestedTests", suggestedTests.stream().sorted().toList());
            result.put("testCommand", "mvn test -Dtest=" + String.join(",", suggestedTests));
        }

        if (ctx.mdOutput()) {
            var sb = new StringBuilder();
            String badge = switch (risk) { case "high" -> "🔴 HIGH"; case "medium" -> "🟡 MEDIUM"; case "low" -> "🟢 LOW"; default -> "⚪ NONE"; };
            sb.append("# Diff Impact: `").append(ref).append("`\n\n");
            sb.append("**Risk Level:** ").append(badge).append("\n\n");
            sb.append("| Metric | Value |\n|--------|-------|\n");
            sb.append("| Changed files | ").append(changedFiles.size()).append(" |\n");
            sb.append("| Changed classes | ").append(details.size()).append(" |\n");
            sb.append("| Impacted classes | ").append(allAffected.size()).append(" |\n\n");
            if (!suggestedTests.isEmpty()) {
                sb.append("## Suggested Tests\n\n```bash\n").append("mvn test -Dtest=").append(String.join(",", suggestedTests)).append("\n```\n\n");
            }
            if (!allAffected.isEmpty()) {
                sb.append("## Affected Classes\n\n");
                for (String cls : allAffected) sb.append("- `").append(cls).append("`\n");
            }
            com.jsrc.app.output.MarkdownWriter.output(sb.toString(), ctx.outDir(), "diff-impact");
            return allAffected.size();
        }

        ctx.formatter().printResult(result);
        return allAffected.size();
    }

    private List<String> getChangedFiles(String rootPath) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("git", "diff", "--name-only", ref);
        pb.directory(new File(rootPath));
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        String output = new String(proc.getInputStream().readAllBytes()).trim();
        proc.waitFor();

        if (output.isEmpty()) return List.of();
        return List.of(output.split("\n")).stream()
                .filter(f -> f.endsWith(".java"))
                .toList();
    }
}
