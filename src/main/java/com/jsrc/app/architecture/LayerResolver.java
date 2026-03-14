package com.jsrc.app.architecture;

import java.util.List;
import java.util.regex.Pattern;

import com.jsrc.app.config.ArchitectureConfig.LayerDef;
import com.jsrc.app.parser.model.ClassInfo;

/**
 * Resolves which architectural layer a class belongs to.
 * Matches by class name pattern and/or annotations.
 */
public class LayerResolver {

    private final List<LayerDef> layers;

    public LayerResolver(List<LayerDef> layers) {
        this.layers = layers;
    }

    /**
     * Returns the layer name for a class, or null if no layer matches.
     */
    public String resolve(ClassInfo ci) {
        for (LayerDef layer : layers) {
            if (matches(ci, layer)) return layer.name();
        }
        return null;
    }

    /**
     * Returns all classes belonging to a given layer.
     */
    public List<ClassInfo> filterByLayer(List<ClassInfo> classes, String layerName) {
        LayerDef layer = layers.stream()
                .filter(l -> l.name().equals(layerName))
                .findFirst().orElse(null);
        if (layer == null) return List.of();

        return classes.stream()
                .filter(ci -> matches(ci, layer))
                .toList();
    }

    private boolean matches(ClassInfo ci, LayerDef layer) {
        // Match by pattern
        if (layer.pattern() != null && !layer.pattern().isEmpty()) {
            if (matchesGlob(ci.name(), layer.pattern())
                    || matchesGlob(ci.qualifiedName(), layer.pattern())) {
                return true;
            }
        }
        // Match by annotation
        if (layer.annotations() != null && !layer.annotations().isEmpty()) {
            for (String ann : layer.annotations()) {
                if (ci.annotations().stream().anyMatch(a -> a.name().equals(ann))) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean matchesGlob(String name, String pattern) {
        String regex = pattern
                .replace(".", "\\.")
                .replace("**/", "(.*/)?")
                .replace("**", ".*")
                .replace("*", "[^.]*");
        return Pattern.matches(regex, name);
    }
}
