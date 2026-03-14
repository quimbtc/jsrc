package com.jsrc.app.architecture;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.jsrc.app.config.ArchitectureConfig;
import com.jsrc.app.config.ArchitectureConfig.RuleDef;
import com.jsrc.app.output.DependencyResult;
import com.jsrc.app.parser.DependencyAnalyzer;
import com.jsrc.app.parser.model.ClassInfo;
import com.jsrc.app.parser.model.MethodInfo;

/**
 * Evaluates architecture rules against parsed classes.
 * Produces a list of violations.
 */
public class RuleEngine {

    private final ArchitectureConfig config;
    private final LayerResolver layerResolver;

    public RuleEngine(ArchitectureConfig config) {
        this.config = config;
        this.layerResolver = new LayerResolver(config.layers());
    }

    /**
     * Evaluates all rules against the given classes.
     */
    public List<Violation> evaluate(List<ClassInfo> classes, List<Path> files) {
        List<Violation> violations = new ArrayList<>();
        for (RuleDef rule : config.rules()) {
            violations.addAll(evaluateRule(rule, classes, files));
        }
        return violations;
    }

    /**
     * Evaluates a single rule by ID.
     */
    public List<Violation> evaluateRule(String ruleId, List<ClassInfo> classes, List<Path> files) {
        return config.rules().stream()
                .filter(r -> r.id().equals(ruleId))
                .findFirst()
                .map(r -> evaluateRule(r, classes, files))
                .orElse(List.of());
    }

    private List<Violation> evaluateRule(RuleDef rule, List<ClassInfo> classes, List<Path> files) {
        List<Violation> violations = new ArrayList<>();

        // deny-import: classes in 'from' layer must not import from 'denyImport' layer
        if (rule.denyImport() != null && !rule.denyImport().isEmpty()) {
            violations.addAll(checkDenyImport(rule, classes, files));
        }

        // require: constructor-injection
        if ("constructor-injection".equals(rule.require())) {
            violations.addAll(checkConstructorInjection(rule, classes, files));
        }

        // deny-annotation: classes in layer must not have annotation on fields
        if (rule.denyAnnotation() != null && !rule.denyAnnotation().isEmpty()) {
            violations.addAll(checkDenyAnnotation(rule, classes));
        }

        return violations;
    }

    private List<Violation> checkDenyImport(RuleDef rule, List<ClassInfo> classes, List<Path> files) {
        List<Violation> violations = new ArrayList<>();
        String fromLayer = rule.from();
        String denyLayer = rule.denyImport();

        // Get class names in the denied layer
        List<ClassInfo> deniedClasses = layerResolver.filterByLayer(classes, denyLayer);
        List<String> deniedNames = deniedClasses.stream()
                .flatMap(c -> List.of(c.name(), c.qualifiedName()).stream())
                .toList();

        // Check each class in 'from' layer
        List<ClassInfo> fromClasses = layerResolver.filterByLayer(classes, fromLayer);
        var analyzer = new DependencyAnalyzer();

        for (ClassInfo ci : fromClasses) {
            DependencyResult deps = analyzer.analyze(files, ci.name());
            if (deps == null) continue;

            for (String imp : deps.imports()) {
                for (String denied : deniedNames) {
                    if (imp.contains(denied)) {
                        violations.add(new Violation(
                                rule.id(), ci.qualifiedName(),
                                rule.description() + ": imports " + imp,
                                "", ci.startLine()));
                    }
                }
            }

            for (var field : deps.fieldDependencies()) {
                if (deniedNames.contains(field.type())) {
                    violations.add(new Violation(
                            rule.id(), ci.qualifiedName(),
                            rule.description() + ": field dependency " + field.type() + " " + field.name(),
                            "", 0));
                }
            }
        }

        return violations;
    }

    private List<Violation> checkConstructorInjection(RuleDef rule, List<ClassInfo> classes, List<Path> files) {
        List<Violation> violations = new ArrayList<>();
        String layer = rule.layer() != null ? rule.layer() : rule.from();
        if (layer == null) return violations;

        List<ClassInfo> targetClasses = layerResolver.filterByLayer(classes, layer);
        var analyzer = new DependencyAnalyzer();

        for (ClassInfo ci : targetClasses) {
            if (ci.isInterface()) continue;
            DependencyResult deps = analyzer.analyze(files, ci.name());
            if (deps == null) continue;

            // Has field deps but no constructor deps → likely field injection
            if (!deps.fieldDependencies().isEmpty() && deps.constructorDependencies().isEmpty()) {
                violations.add(new Violation(
                        rule.id(), ci.qualifiedName(),
                        rule.description() + ": has " + deps.fieldDependencies().size()
                                + " field(s) but no constructor injection",
                        "", ci.startLine()));
            }
        }

        return violations;
    }

    private List<Violation> checkDenyAnnotation(RuleDef rule, List<ClassInfo> classes) {
        List<Violation> violations = new ArrayList<>();
        String layer = rule.layer() != null ? rule.layer() : rule.from();
        if (layer == null) return violations;

        String denyAnn = rule.denyAnnotation();
        List<ClassInfo> targetClasses = layerResolver.filterByLayer(classes, layer);

        for (ClassInfo ci : targetClasses) {
            // Check method-level annotations
            for (MethodInfo m : ci.methods()) {
                if (m.hasAnnotation(denyAnn)) {
                    violations.add(new Violation(
                            rule.id(), ci.qualifiedName(),
                            "Method " + m.name() + "() has denied annotation @" + denyAnn,
                            "", m.startLine()));
                }
            }

            // Check class-level annotations
            if (ci.annotations().stream().anyMatch(a -> a.name().equals(denyAnn))) {
                violations.add(new Violation(
                        rule.id(), ci.qualifiedName(),
                        "Class has denied annotation @" + denyAnn,
                        "", ci.startLine()));
            }
        }

        return violations;
    }
}
