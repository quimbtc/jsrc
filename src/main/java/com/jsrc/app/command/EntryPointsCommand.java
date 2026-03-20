package com.jsrc.app.command;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.jsrc.app.parser.model.ClassInfo;

/**
 * Lists public entry points: REST endpoints, main methods, scheduled tasks, listeners.
 * Helps orchestrators know what already exists before assigning new features.
 */
public class EntryPointsCommand implements Command {

    private static final Set<String> REST_ANNOTATIONS = Set.of(
            "GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "PatchMapping",
            "RequestMapping", "RestController", "Controller");
    private static final Set<String> SCHEDULED_ANNOTATIONS = Set.of(
            "Scheduled", "Schedules");
    private static final Set<String> LISTENER_ANNOTATIONS = Set.of(
            "EventListener", "KafkaListener", "RabbitListener", "JmsListener",
            "StreamListener", "TransactionalEventListener");

    private final String packageFilter;

    public EntryPointsCommand(String packageFilter) {
        this.packageFilter = packageFilter;
    }

    @Override
    public int execute(CommandContext ctx) {
        var allClasses = ctx.getAllClasses();
        List<Map<String, Object>> entryPoints = new ArrayList<>();

        for (ClassInfo ci : allClasses) {
            if (packageFilter != null && !ci.packageName().startsWith(packageFilter)
                    && !ci.packageName().contains(packageFilter)) continue;

            // REST controllers
            boolean isRestController = ci.annotations().stream()
                    .anyMatch(a -> REST_ANNOTATIONS.contains(a.name()));
            if (isRestController) {
                for (var m : ci.methods()) {
                    boolean isEndpoint = m.annotations().stream()
                            .anyMatch(a -> REST_ANNOTATIONS.contains(a.name()));
                    if (isEndpoint) {
                        var ep = new LinkedHashMap<String, Object>();
                        ep.put("type", "rest");
                        ep.put("class", ctx.qualify(ci.name()));
                        ep.put("method", m.name());
                        ep.put("signature", m.signature());
                        m.annotations().stream()
                                .filter(a -> REST_ANNOTATIONS.contains(a.name()))
                                .findFirst()
                                .ifPresent(a -> ep.put("annotation", "@" + a.name()));
                        entryPoints.add(ep);
                    }
                }
            }

            // Main methods
            for (var m : ci.methods()) {
                if (m.name().equals("main") && m.signature() != null
                        && m.signature().contains("String[]")) {
                    var ep = new LinkedHashMap<String, Object>();
                    ep.put("type", "main");
                    ep.put("class", ctx.qualify(ci.name()));
                    ep.put("method", "main");
                    entryPoints.add(ep);
                }
            }

            // Scheduled
            for (var m : ci.methods()) {
                boolean isScheduled = m.annotations().stream()
                        .anyMatch(a -> SCHEDULED_ANNOTATIONS.contains(a.name()));
                if (isScheduled) {
                    var ep = new LinkedHashMap<String, Object>();
                    ep.put("type", "scheduled");
                    ep.put("class", ctx.qualify(ci.name()));
                    ep.put("method", m.name());
                    ep.put("annotation", "@Scheduled");
                    entryPoints.add(ep);
                }
            }

            // Listeners
            for (var m : ci.methods()) {
                boolean isListener = m.annotations().stream()
                        .anyMatch(a -> LISTENER_ANNOTATIONS.contains(a.name()));
                if (isListener) {
                    var ep = new LinkedHashMap<String, Object>();
                    ep.put("type", "listener");
                    ep.put("class", ctx.qualify(ci.name()));
                    ep.put("method", m.name());
                    m.annotations().stream()
                            .filter(a -> LISTENER_ANNOTATIONS.contains(a.name()))
                            .findFirst()
                            .ifPresent(a -> ep.put("annotation", "@" + a.name()));
                    entryPoints.add(ep);
                }
            }
        }

        ctx.formatter().printResult(entryPoints);
        return entryPoints.size();
    }
}
