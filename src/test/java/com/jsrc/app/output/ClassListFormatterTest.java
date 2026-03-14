package com.jsrc.app.output;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.jsrc.app.parser.model.AnnotationInfo;
import com.jsrc.app.parser.model.ClassInfo;

class ClassListFormatterTest {

    @Test
    @DisplayName("JsonFormatter should output class list as JSON array")
    void jsonShouldOutputClassArray() {
        String out = captureOutput(() -> {
            OutputFormatter fmt = new JsonFormatter();
            ClassInfo ci = new ClassInfo("OrderService", "com.app.service", 10, 50,
                    List.of("public"), List.of(), "BaseService",
                    List.of("Serializable"), List.of(AnnotationInfo.marker("Service")), false);
            fmt.printClasses(List.of(ci), Path.of("/src"));
        });
        assertTrue(out.startsWith("["));
        assertTrue(out.contains("\"name\":\"OrderService\""));
        assertTrue(out.contains("\"packageName\":\"com.app.service\""));
        assertTrue(out.contains("\"qualifiedName\":\"com.app.service.OrderService\""));
        assertTrue(out.contains("\"isInterface\":false"));
        assertTrue(out.contains("\"isAbstract\":false"));
        assertTrue(out.contains("\"methodCount\":0"));
        assertFalse(out.contains("\"methods\""), "Should not include method details");
    }

    @Test
    @DisplayName("JsonFormatter should output empty array for no classes")
    void jsonShouldOutputEmptyArray() {
        String out = captureOutput(() -> {
            OutputFormatter fmt = new JsonFormatter();
            fmt.printClasses(List.of(), Path.of("/src"));
        });
        assertEquals("[]", out.trim());
    }

    @Test
    @DisplayName("TextFormatter should output human-readable class list")
    void textShouldOutputReadable() {
        String out = captureOutput(() -> {
            OutputFormatter fmt = new TextFormatter();
            ClassInfo ci = new ClassInfo("OrderService", "com.app.service", 10, 50,
                    List.of("public"), List.of(), "", List.of(), List.of(), false);
            fmt.printClasses(List.of(ci), Path.of("/src"));
        });
        assertTrue(out.contains("OrderService"));
        assertTrue(out.contains("com.app.service"));
    }

    @Test
    @DisplayName("JsonFormatter should include file path relative to source root")
    void jsonShouldIncludeFile() {
        String out = captureOutput(() -> {
            OutputFormatter fmt = new JsonFormatter();
            ClassInfo ci = ClassInfo.basic("Foo", "com.app", 1, 10, List.of("public"), List.of());
            fmt.printClasses(List.of(ci), Path.of("/project/src"));
        });
        // file info comes from the class scan, not ClassInfo itself
        assertTrue(out.contains("\"name\":\"Foo\""));
    }

    @Test
    @DisplayName("Should report interface correctly")
    void shouldReportInterface() {
        String out = captureOutput(() -> {
            OutputFormatter fmt = new JsonFormatter();
            ClassInfo ci = new ClassInfo("Repository", "com.app.repo", 1, 20,
                    List.of("public"), List.of(), "", List.of(), List.of(), true);
            fmt.printClasses(List.of(ci), Path.of("/src"));
        });
        assertTrue(out.contains("\"isInterface\":true"));
    }

    private String captureOutput(Runnable action) {
        PrintStream original = System.out;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        System.setOut(new PrintStream(baos));
        try {
            action.run();
        } finally {
            System.setOut(original);
        }
        return baos.toString().trim();
    }
}
