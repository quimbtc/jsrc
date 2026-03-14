package com.jsrc.app.parser;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.jsrc.app.parser.model.ClassInfo;
import com.jsrc.app.parser.model.MethodInfo;

class TreeSitterParserTest {

    private TreeSitterParser parser;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        parser = new TreeSitterParser("java");
    }

    @Test
    @DisplayName("Should report correct language")
    void shouldReportLanguage() {
        assertEquals("java", parser.getLanguage());
    }

    @Test
    @DisplayName("Should implement CodeParser")
    void shouldImplementCodeParser() {
        assertInstanceOf(CodeParser.class, parser);
    }

    @Test
    @DisplayName("Should reject null/blank language")
    void shouldRejectInvalidLanguage() {
        assertThrows(IllegalArgumentException.class, () -> new TreeSitterParser(null));
        assertThrows(IllegalArgumentException.class, () -> new TreeSitterParser(""));
        assertThrows(IllegalArgumentException.class, () -> new TreeSitterParser("cobol"));
    }

    @Test
    @DisplayName("Should return empty for null/missing path or blank method")
    void shouldReturnEmptyForInvalidInput() {
        assertTrue(parser.findMethods(null, "test").isEmpty());
        assertTrue(parser.findMethods(tempDir.resolve("nope.java"), "test").isEmpty());
    }

    @Test
    @DisplayName("Should find a single method with basic info")
    void shouldFindSingleMethod() throws IOException {
        Path file = writeFile("Hello.java", """
                public class Hello {
                    public void greet() {
                        System.out.println("Hi");
                    }
                }
                """);

        List<MethodInfo> results = parser.findMethods(file, "greet");
        assertEquals(1, results.size());

        MethodInfo m = results.getFirst();
        assertEquals("greet", m.name());
        assertEquals("Hello", m.className());
        assertEquals("void", m.returnType());
        assertTrue(m.modifiers().contains("public"));
        assertTrue(m.annotations().isEmpty(), "TreeSitter should not extract annotations");
        assertTrue(m.thrownExceptions().isEmpty(), "TreeSitter should not extract throws");
        assertNull(m.javadoc(), "TreeSitter should not extract javadoc");
    }

    @Test
    @DisplayName("Should find overloaded methods and filter by param types")
    void shouldFilterByParameterTypes() throws IOException {
        Path file = writeFile("Calc.java", """
                public class Calc {
                    public int add(int a, int b) { return a + b; }
                    public double add(double a, double b) { return a + b; }
                }
                """);

        assertEquals(2, parser.findMethods(file, "add").size());
        assertEquals(1, parser.findMethods(file, "add", List.of("int", "int")).size());
        assertEquals(1, parser.findMethods(file, "add", List.of("double", "double")).size());
        assertTrue(parser.findMethods(file, "add", List.of("String")).isEmpty());
    }

    @Test
    @DisplayName("Should find all methods in a file")
    void shouldFindAllMethods() throws IOException {
        Path file = writeFile("Multi.java", """
                public class Multi {
                    public void a() {}
                    private int b() { return 1; }
                    protected String c(String x) { return x; }
                }
                """);

        List<MethodInfo> all = parser.findAllMethods(file);
        assertEquals(3, all.size());
    }

    @Test
    @DisplayName("Should parse classes with basic info")
    void shouldParseClasses() throws IOException {
        Path file = writeFile("Two.java", """
                class Alpha {
                    public void doA() {}
                }
                class Beta {
                    public void doB() {}
                    public void doC() {}
                }
                """);

        List<ClassInfo> classes = parser.parseClasses(file);
        assertEquals(2, classes.size());
        assertEquals("Alpha", classes.get(0).name());
        assertEquals(1, classes.get(0).methods().size());
        assertEquals("Beta", classes.get(1).name());
        assertEquals(2, classes.get(1).methods().size());
    }

    @Test
    @DisplayName("findMethodsByAnnotation should return empty (not supported)")
    void shouldReturnEmptyForAnnotationSearch() throws IOException {
        Path file = writeFile("Ann.java", """
                public class Ann {
                    @Override
                    public String toString() { return ""; }
                }
                """);

        assertTrue(parser.findMethodsByAnnotation(file, "Override").isEmpty());
    }

    @Test
    @DisplayName("Should handle empty file")
    void shouldHandleEmptyFile() throws IOException {
        Path file = writeFile("Empty.java", "");
        assertTrue(parser.findMethods(file, "any").isEmpty());
        assertTrue(parser.findAllMethods(file).isEmpty());
        assertTrue(parser.parseClasses(file).isEmpty());
    }

    private Path writeFile(String name, String content) throws IOException {
        Path file = tempDir.resolve(name);
        Files.writeString(file, content);
        return file;
    }
}
