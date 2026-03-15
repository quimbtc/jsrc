package com.jsrc.app.config;

import java.util.List;

/**
 * Architecture configuration for layer-based validation and invoker resolution.
 */
public record ArchitectureConfig(
        List<LayerDef> layers,
        List<RuleDef> rules,
        List<String> endpointAnnotations,
        List<InvokerDef> invokers,
        List<String> chainStopMethods
) {
    public static ArchitectureConfig empty() {
        return new ArchitectureConfig(List.of(), List.of(), List.of(), List.of(), List.of());
    }

    /** Backward-compatible constructor without chainStopMethods. */
    public ArchitectureConfig(List<LayerDef> layers, List<RuleDef> rules,
                              List<String> endpointAnnotations, List<InvokerDef> invokers) {
        this(layers, rules, endpointAnnotations, invokers, List.of());
    }

    /**
     * Defines an architectural layer.
     *
     * @param name        layer identifier (e.g. "controller", "service")
     * @param pattern     glob pattern for class names
     * @param annotations annotation names that mark classes in this layer
     */
    public record LayerDef(String name, String pattern, List<String> annotations) {}

    /**
     * Defines an architecture rule.
     *
     * @param id              unique rule ID
     * @param description     human-readable description
     * @param from            source layer (for deny-import rules)
     * @param layer           target layer (for require rules)
     * @param denyImport      deny imports from this layer
     * @param require         requirement type (e.g. "constructor-injection")
     * @param denyAnnotation  annotation to deny (e.g. "Autowired")
     */
    public record RuleDef(
            String id, String description,
            String from, String layer,
            String denyImport, String require, String denyAnnotation
    ) {}

    /**
     * Defines a reflective invoker pattern.
     *
     * @param method         invoker method name (e.g. "ejecutarMetodo")
     * @param targetArg      index of argument containing target method name
     * @param resolveClass   class name convention to resolve target (e.g. "adaptadorBean")
     * @param callerSuffixes suffixes to strip from caller class name (e.g. ["Detalle", "Vista"])
     */
    public record InvokerDef(String method, int targetArg, String resolveClass, List<String> callerSuffixes) {
        public InvokerDef(String method, int targetArg, String resolveClass) {
            this(method, targetArg, resolveClass, List.of("Detalle", "Vista", "View", "Form", "Panel", "Dialog"));
        }
    }
}
