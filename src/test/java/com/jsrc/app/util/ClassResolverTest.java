package com.jsrc.app.util;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.jsrc.app.parser.model.ClassInfo;
import com.jsrc.app.util.ClassResolver.Resolution;

class ClassResolverTest {

    @Test
    @DisplayName("Should resolve unique simple name")
    void shouldResolveUnique() {
        var classes = List.of(
                classInfo("OrderService", "com.app.service"),
                classInfo("PaymentService", "com.app.service")
        );

        var result = ClassResolver.resolve(classes, "OrderService");
        assertInstanceOf(Resolution.Found.class, result);
        assertEquals("OrderService", ((Resolution.Found) result).classInfo().name());
    }

    @Test
    @DisplayName("Should resolve by qualified name")
    void shouldResolveQualified() {
        var classes = List.of(
                classInfo("OrderService", "com.app.service"),
                classInfo("OrderService", "com.legacy")
        );

        var result = ClassResolver.resolve(classes, "com.app.service.OrderService");
        assertInstanceOf(Resolution.Found.class, result);
        assertEquals("com.app.service", ((Resolution.Found) result).classInfo().packageName());
    }

    @Test
    @DisplayName("Should return Ambiguous for duplicate simple names")
    void shouldReturnAmbiguous() {
        var classes = List.of(
                classInfo("OrderService", "com.app.service"),
                classInfo("OrderService", "com.legacy")
        );

        var result = ClassResolver.resolve(classes, "OrderService");
        assertInstanceOf(Resolution.Ambiguous.class, result);
        var ambiguous = (Resolution.Ambiguous) result;
        assertEquals(2, ambiguous.candidates().size());
        assertTrue(ambiguous.candidates().contains("com.app.service.OrderService"));
        assertTrue(ambiguous.candidates().contains("com.legacy.OrderService"));
    }

    @Test
    @DisplayName("Should return NotFound for unknown class")
    void shouldReturnNotFound() {
        var classes = List.of(classInfo("Other", "com.app"));
        var result = ClassResolver.resolve(classes, "Missing");
        assertInstanceOf(Resolution.NotFound.class, result);
    }

    @Test
    @DisplayName("Qualified name should take precedence over simple name")
    void qualifiedPrecedence() {
        var classes = List.of(
                classInfo("OrderService", "com.app.service"),
                classInfo("OrderService", "com.legacy"),
                classInfo("OrderService", "com.test")
        );

        // Qualified → exact match, no ambiguity
        var result = ClassResolver.resolve(classes, "com.legacy.OrderService");
        assertInstanceOf(Resolution.Found.class, result);
        assertEquals("com.legacy", ((Resolution.Found) result).classInfo().packageName());
    }

    private ClassInfo classInfo(String name, String pkg) {
        return ClassInfo.basic(name, pkg, 1, 50, List.of("public"), List.of());
    }
}
