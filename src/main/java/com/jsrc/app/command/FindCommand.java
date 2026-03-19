package com.jsrc.app.command;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.jsrc.app.parser.model.ClassInfo;

/**
 * Semantic search using a Java concept synonym dictionary.
 * Expands natural-language queries to match code patterns.
 * E.g. "handle database errors" → finds DatabaseExceptionHandler.
 */
public class FindCommand implements Command {

    private static final Map<String, List<String>> SYNONYMS = Map.ofEntries(
            Map.entry("database", List.of("database", "datasource", "jdbc", "jpa", "repository", "sql", "connection", "pool", "persistence", "hibernate", "entity", "db")),
            Map.entry("error", List.of("error", "exception", "failureanalyzer", "handler", "fallback", "catch", "throw", "fault")),
            Map.entry("handle", List.of("handle", "handler", "process", "catch", "on", "listener", "intercept")),
            Map.entry("auth", List.of("security", "authentication", "authorization", "token", "credential", "principal", "login", "oauth")),
            Map.entry("http", List.of("controller", "servlet", "request", "response", "endpoint", "mapping", "rest", "web", "filter")),
            Map.entry("config", List.of("configuration", "properties", "settings", "preference", "profile", "environment", "yaml")),
            Map.entry("test", List.of("test", "mock", "stub", "fixture", "assert", "verify", "testcontainers")),
            Map.entry("cache", List.of("cache", "caffeine", "redis", "ehcache", "caching", "evict")),
            Map.entry("message", List.of("message", "queue", "kafka", "rabbit", "jms", "event", "listener", "publisher", "consumer")),
            Map.entry("log", List.of("logger", "log", "logging", "slf4j", "logback", "log4j")),
            Map.entry("serial", List.of("serializ", "jackson", "json", "xml", "marshal", "objectmapper", "gson")),
            Map.entry("async", List.of("async", "future", "completable", "executor", "thread", "parallel", "reactive", "flux", "mono")),
            Map.entry("valid", List.of("valid", "constraint", "validator", "bindingresult", "error", "reject")),
            Map.entry("schedule", List.of("schedule", "cron", "timer", "periodic", "trigger", "taskscheduler")),
            Map.entry("file", List.of("file", "path", "resource", "stream", "reader", "writer", "io", "nio")),
            Map.entry("ssl", List.of("ssl", "tls", "certificate", "keystore", "truststore", "pem", "jks"))
    );

    private static final Set<String> STOP_WORDS = Set.of(
            "the", "a", "an", "in", "on", "at", "to", "for", "of", "with",
            "is", "are", "was", "were", "be", "it", "this", "that", "do", "does");

    private final String query;

    public FindCommand(String query) {
        this.query = query;
    }

    @Override
    public int execute(CommandContext ctx) {
        String[] words = query.toLowerCase().split("[\\s,.:;!?]+");
        List<String> queryWords = new ArrayList<>();
        for (String w : words) {
            if (!STOP_WORDS.contains(w) && w.length() > 1) queryWords.add(w);
        }

        // Expand to synonyms (with basic stemming: strip trailing s/es/ing/ed)
        Map<String, List<String>> expanded = new LinkedHashMap<>();
        for (String word : queryWords) {
            List<String> synonyms = findSynonyms(word);
            expanded.put(word, synonyms);
        }

        // Search classes
        var allClasses = ctx.getAllClasses();
        List<ScoredMatch> scored = new ArrayList<>();

        for (ClassInfo ci : allClasses) {
            int score = 0;
            List<String> matchedConcepts = new ArrayList<>();
            String nameLower = ci.name().toLowerCase();
            String qualLower = ci.qualifiedName().toLowerCase();

            for (var entry : expanded.entrySet()) {
                boolean matched = false;
                for (String syn : entry.getValue()) {
                    if (nameLower.contains(syn) || qualLower.contains(syn)) {
                        score += 5;
                        matched = true;
                        break;
                    }
                    // Check method names
                    boolean inMethod = ci.methods().stream()
                            .anyMatch(m -> m.name().toLowerCase().contains(syn));
                    if (inMethod) {
                        score += 3;
                        matched = true;
                        break;
                    }
                }
                if (matched) matchedConcepts.add(entry.getKey());
            }

            if (score > 0 && matchedConcepts.size() >= Math.min(2, expanded.size())) {
                scored.add(new ScoredMatch(ci.qualifiedName(), score, matchedConcepts));
            }
        }

        scored.sort(Comparator.comparingInt((ScoredMatch s) -> -s.score)
                .thenComparing(s -> s.name));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", query);
        result.put("expanded", expanded.entrySet().stream()
                .map(e -> e.getKey() + "→" + e.getValue().subList(0, Math.min(3, e.getValue().size())))
                .toList());
        result.put("matches", scored.stream().limit(15).map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", s.name);
            m.put("score", s.score);
            m.put("matchedConcepts", s.concepts);
            return m;
        }).toList());
        result.put("totalMatches", scored.size());

        ctx.formatter().printResult(result);
        return scored.size();
    }

    private List<String> findSynonyms(String word) {
        // Direct match
        if (SYNONYMS.containsKey(word)) return SYNONYMS.get(word);
        // Check if word IS a synonym value
        for (var entry : SYNONYMS.entrySet()) {
            if (entry.getValue().contains(word)) return entry.getValue();
        }
        // Basic stemming: try without trailing s, es, ing, ed
        for (String stem : new String[]{
                word.endsWith("s") ? word.substring(0, word.length() - 1) : null,
                word.endsWith("es") ? word.substring(0, word.length() - 2) : null,
                word.endsWith("ing") ? word.substring(0, word.length() - 3) : null,
                word.endsWith("ed") ? word.substring(0, word.length() - 2) : null}) {
            if (stem != null && SYNONYMS.containsKey(stem)) return SYNONYMS.get(stem);
            if (stem != null) {
                for (var entry : SYNONYMS.entrySet()) {
                    if (entry.getValue().contains(stem)) return entry.getValue();
                }
            }
        }
        return List.of(word); // literal fallback
    }

    private record ScoredMatch(String name, int score, List<String> concepts) {}
}
