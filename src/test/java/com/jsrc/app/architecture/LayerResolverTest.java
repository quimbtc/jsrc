package com.jsrc.app.architecture;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.jsrc.app.config.ArchitectureConfig.LayerDef;
import com.jsrc.app.parser.model.AnnotationInfo;
import com.jsrc.app.parser.model.ClassInfo;

class LayerResolverTest {

    private final List<LayerDef> layers = List.of(
            new LayerDef("controller", "**/*Controller", List.of("RestController", "Controller")),
            new LayerDef("service", "**/*Service", List.of("Service")),
            new LayerDef("repository", "**/*Repository", List.of("Repository"))
    );

    @Test
    @DisplayName("Should resolve layer by class name pattern")
    void shouldResolveByPattern() {
        var resolver = new LayerResolver(layers);
        assertEquals("controller", resolver.resolve(classInfo("OrderController")));
        assertEquals("service", resolver.resolve(classInfo("PaymentService")));
        assertEquals("repository", resolver.resolve(classInfo("UserRepository")));
    }

    @Test
    @DisplayName("Should resolve layer by annotation")
    void shouldResolveByAnnotation() {
        var resolver = new LayerResolver(layers);
        var ci = new ClassInfo("OrderHandler", "com.app", 1, 50,
                List.of("public"), List.of(), "", List.of(),
                List.of(AnnotationInfo.marker("RestController")), false);
        assertEquals("controller", resolver.resolve(ci));
    }

    @Test
    @DisplayName("Should return null for unmatched class")
    void shouldReturnNullForUnmatched() {
        var resolver = new LayerResolver(layers);
        assertNull(resolver.resolve(classInfo("HelperUtils")));
    }

    @Test
    @DisplayName("Should filter classes by layer name")
    void shouldFilterByLayer() {
        var resolver = new LayerResolver(layers);
        var classes = List.of(
                classInfo("OrderController"),
                classInfo("PaymentService"),
                classInfo("OrderService"),
                classInfo("UserRepository"),
                classInfo("Utils")
        );

        var services = resolver.filterByLayer(classes, "service");
        assertEquals(2, services.size());
        assertTrue(services.stream().allMatch(c -> c.name().endsWith("Service")));
    }

    @Test
    @DisplayName("Should return empty for unknown layer")
    void shouldReturnEmptyForUnknownLayer() {
        var resolver = new LayerResolver(layers);
        assertTrue(resolver.filterByLayer(List.of(classInfo("Foo")), "unknown").isEmpty());
    }

    private ClassInfo classInfo(String name) {
        return ClassInfo.basic(name, "com.app", 1, 50, List.of("public"), List.of());
    }
}
