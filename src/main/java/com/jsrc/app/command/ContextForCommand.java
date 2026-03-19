package com.jsrc.app.command;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Auto-generates a plan of jsrc commands for a given task description.
 * Detects intent (fix/feature/explore/change) and generates optimal steps
 * with estimated token costs.
 * <p>
 * The agent executes the plan step-by-step instead of reasoning
 * about which commands to use.
 */
public class ContextForCommand implements Command {

    private static final Pattern CLASS_METHOD_PATTERN =
            Pattern.compile("([A-Z][a-zA-Z0-9]+)\\.([a-z][a-zA-Z0-9]+)");
    private static final Pattern CLASS_ONLY_PATTERN =
            Pattern.compile("\\b([A-Z][a-zA-Z0-9]{2,})\\b");

    private static final Set<String> FIX_KEYWORDS = Set.of(
            "fix", "bug", "error", "exception", "null", "npe", "stacktrace",
            "crash", "fail", "broken", "issue", "wrong", "incorrect");
    private static final Set<String> FEATURE_KEYWORDS = Set.of(
            "add", "create", "new", "implement", "support", "feature", "extend",
            "integrate", "build", "develop");
    private static final Set<String> EXPLORE_KEYWORDS = Set.of(
            "understand", "review", "explore", "audit", "how", "what", "where",
            "explain", "describe", "overview", "analyze");
    private static final Set<String> CHANGE_KEYWORDS = Set.of(
            "change", "modify", "refactor", "rename", "update", "migrate",
            "move", "extract", "replace", "remove");
    private static final Set<String> STOP_WORDS = Set.of(
            "the", "a", "an", "in", "on", "at", "to", "for", "of", "with",
            "is", "are", "was", "were", "be", "been", "being", "it", "this");

    private final String taskDescription;
    private final int budgetTokens;

    public ContextForCommand(String taskDescription) {
        this(taskDescription, 2000);
    }

    public ContextForCommand(String taskDescription, int budgetTokens) {
        this.taskDescription = taskDescription;
        this.budgetTokens = budgetTokens > 0 ? budgetTokens : 2000;
    }

    @Override
    public int execute(CommandContext ctx) {
        if (taskDescription == null || taskDescription.isBlank()) {
            ctx.formatter().printResult(Map.of("error", "Task description required"));
            return 0;
        }

        String lower = taskDescription.toLowerCase();
        String[] words = lower.split("[\\s,.:;!?]+");

        // Extract class.method references
        String classMethod = null;
        String className = null;
        Matcher cm = CLASS_METHOD_PATTERN.matcher(taskDescription);
        if (cm.find()) {
            className = cm.group(1);
            classMethod = cm.group(1) + "." + cm.group(2);
        } else {
            Matcher co = CLASS_ONLY_PATTERN.matcher(taskDescription);
            if (co.find()) {
                className = co.group(1);
            }
        }

        // Extract keywords (non-stop words)
        List<String> keywords = new ArrayList<>();
        for (String w : words) {
            if (!STOP_WORDS.contains(w) && w.length() > 2) keywords.add(w);
        }

        // Detect intent
        String intent = detectIntent(words);

        // Generate plan
        List<Map<String, Object>> steps = new ArrayList<>();
        int usedTokens = 0;

        switch (intent) {
            case "fix" -> {
                if (classMethod != null) {
                    steps.add(step(steps.size() + 1, "--read " + classMethod, 80, "Read failing method"));
                    steps.add(step(steps.size() + 1, "--mini " + className, 120, "Understand class context"));
                    steps.add(step(steps.size() + 1, "--impact " + classMethod, 200, "Check who is affected"));
                    steps.add(step(steps.size() + 1, "--validate <fix>", 55, "Verify fix method exists"));
                } else if (className != null) {
                    steps.add(step(steps.size() + 1, "--mini " + className, 120, "Understand class"));
                    steps.add(step(steps.size() + 1, "--deps " + className, 200, "Check dependencies"));
                    steps.add(step(steps.size() + 1, "--smells " + className, 150, "Detect code smells"));
                } else {
                    steps.add(step(steps.size() + 1, "--scope " + String.join(" ", keywords.subList(0, Math.min(3, keywords.size()))), 300, "Find relevant classes"));
                }
            }
            case "feature" -> {
                String scopeKeywords = keywords.stream()
                        .filter(k -> !FEATURE_KEYWORDS.contains(k))
                        .reduce((a, b) -> a + " " + b).orElse(keywords.isEmpty() ? taskDescription : keywords.getFirst());
                steps.add(step(steps.size() + 1, "--scope " + scopeKeywords, 300, "Find where feature lives"));
                steps.add(step(steps.size() + 1, "--style", 50, "Know project conventions"));
                if (className != null) {
                    steps.add(step(steps.size() + 1, "--mini " + className, 120, "Understand target class"));
                    steps.add(step(steps.size() + 1, "--related " + className, 400, "Find related classes"));
                }
                steps.add(step(steps.size() + 1, "--snippet <pattern>", 200, "Get code template"));
                steps.add(step(steps.size() + 1, "--checklist <method>", 150, "Plan the change"));
            }
            case "change" -> {
                if (classMethod != null) {
                    steps.add(step(steps.size() + 1, "--read " + classMethod, 80, "Read current implementation"));
                    steps.add(step(steps.size() + 1, "--impact " + classMethod, 200, "Assess change impact"));
                    steps.add(step(steps.size() + 1, "--callers " + classMethod, 150, "List all callers"));
                    steps.add(step(steps.size() + 1, "--checklist " + classMethod, 150, "Step-by-step plan"));
                } else if (className != null) {
                    steps.add(step(steps.size() + 1, "--mini " + className, 120, "Understand class"));
                    steps.add(step(steps.size() + 1, "--related " + className, 400, "Find affected classes"));
                }
            }
            default -> { // explore
                steps.add(step(steps.size() + 1, "--overview", 50, "Codebase stats"));
                if (!keywords.isEmpty()) {
                    steps.add(step(steps.size() + 1, "--scope " + keywords.getFirst(), 300, "Find relevant area"));
                }
                if (className != null) {
                    steps.add(step(steps.size() + 1, "--mini " + className, 120, "Quick summary"));
                    steps.add(step(steps.size() + 1, "--related " + className, 400, "Explore neighborhood"));
                } else {
                    steps.add(step(steps.size() + 1, "--map", 500, "Repo overview map"));
                }
            }
        }

        // Trim to budget
        List<Map<String, Object>> trimmedSteps = new ArrayList<>();
        for (var s : steps) {
            int est = ((Number) s.get("estTokens")).intValue();
            if (usedTokens + est > budgetTokens && !trimmedSteps.isEmpty()) break;
            trimmedSteps.add(s);
            usedTokens += est;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("task", taskDescription);
        result.put("intent", intent);
        if (className != null) result.put("detectedClass", className);
        if (classMethod != null) result.put("detectedMethod", classMethod);
        result.put("readPlan", trimmedSteps);
        result.put("totalEstTokens", usedTokens);
        result.put("budgetRemaining", budgetTokens - usedTokens);

        ctx.formatter().printResult(result);
        return trimmedSteps.size();
    }

    private String detectIntent(String[] words) {
        int fix = 0, feature = 0, explore = 0, change = 0;
        for (String w : words) {
            if (FIX_KEYWORDS.contains(w)) fix++;
            if (FEATURE_KEYWORDS.contains(w)) feature++;
            if (EXPLORE_KEYWORDS.contains(w)) explore++;
            if (CHANGE_KEYWORDS.contains(w)) change++;
        }
        if (fix >= feature && fix >= explore && fix >= change && fix > 0) return "fix";
        if (feature >= fix && feature >= explore && feature >= change && feature > 0) return "feature";
        if (change >= fix && change >= feature && change >= explore && change > 0) return "change";
        if (explore > 0) return "explore";
        return "explore"; // default
    }

    private Map<String, Object> step(int num, String command, int estTokens, String reason) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("step", num);
        s.put("command", command);
        s.put("estTokens", estTokens);
        s.put("reason", reason);
        return s;
    }
}
