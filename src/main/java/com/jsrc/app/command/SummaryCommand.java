package com.jsrc.app.command;

import java.nio.file.Path;

import java.util.Comparator;
import java.util.List;

import com.jsrc.app.parser.model.ClassInfo;
import com.jsrc.app.parser.model.MethodInfo;
import com.jsrc.app.util.ClassResolver;

public class SummaryCommand implements Command {
    private final String className;

    public SummaryCommand(String className) {
        this.className = className;
    }

    @Override
    public int execute(CommandContext ctx) {
        var allClasses = ctx.getAllClasses();
        ClassInfo ci = resolveOrExit(allClasses, className);
        if (ci == null) return 0;

        // Compact mode (default): limit to top 20 methods sorted by caller count
        if (!ctx.fullOutput() && ci.methods().size() > 20) {
            var graph = ctx.callGraph();
            String cn = ci.name();
            List<MethodInfo> trimmed = ci.methods().stream()
                    .sorted(Comparator.<MethodInfo, Integer>comparing(m ->
                            graph.findMethodsByName(m.name()).stream()
                                    .filter(r -> r.className().equals(cn))
                                    .mapToInt(r -> graph.getCallersOf(r).size())
                                    .sum())
                            .reversed()
                            .thenComparing(MethodInfo::name))
                    .limit(20)
                    .toList();
            ci = ci.withMethods(trimmed);
        }

        String filePath = ctx.indexed() != null
                ? ctx.indexed().findFileForClass(ci.name()).orElse("") : "";
        ctx.formatter().printClassSummary(ci, Path.of(filePath));
        return 1;
    }

    /** Find the closest class name by Levenshtein distance (max 3 edits). */
    private static String findClosest(java.util.List<ClassInfo> allClasses, String target) {
        String best = null;
        int bestDist = 4; // max distance threshold
        for (var ci : allClasses) {
            int dist = levenshtein(target.toLowerCase(), ci.name().toLowerCase());
            if (dist < bestDist) {
                bestDist = dist;
                best = ci.name();
            }
        }
        return best;
    }

    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                curr[j] = a.charAt(i - 1) == b.charAt(j - 1) ? prev[j - 1]
                        : 1 + Math.min(prev[j - 1], Math.min(prev[j], curr[j - 1]));
            }
            int[] tmp = prev; prev = curr; curr = tmp;
        }
        return prev[b.length()];
    }

    static ClassInfo resolveOrExit(java.util.List<ClassInfo> allClasses, String className) {
        var resolution = ClassResolver.resolve(allClasses, className);
        return switch (resolution) {
            case ClassResolver.Resolution.Found found -> found.classInfo();
            case ClassResolver.Resolution.Ambiguous ambiguous -> {
                ClassResolver.printAmbiguous(ambiguous.candidates(), className);
                yield null;
            }
            case ClassResolver.Resolution.NotFound n -> {
                // Fuzzy suggest + reference count for better agent UX
                String suggestion = findClosest(allClasses, className);
                // Count how many indexed classes import this name (best-effort)
                long refCount = 0;

                StringBuilder msg = new StringBuilder();
                msg.append(String.format("Class '%s' not found in index.", className));
                if (suggestion != null) {
                    msg.append(String.format(" Did you mean '%s'?", suggestion));
                }
                System.err.println(msg);
                yield null;
            }
        };
    }
}
