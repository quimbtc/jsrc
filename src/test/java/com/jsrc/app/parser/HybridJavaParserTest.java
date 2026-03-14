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

class HybridJavaParserTest {

    private HybridJavaParser parser;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        parser = new HybridJavaParser();
    }

    @Test
    @DisplayName("Should report java language")
    void shouldReportLanguage() {
        assertEquals("java", parser.getLanguage());
    }

    // ---- enrichment: annotations ----

    @Test
    @DisplayName("Should extract annotations on methods")
    void shouldExtractAnnotations() throws IOException {
        Path file = writeFile("Annotated.java", """
                import java.util.List;
                public class Annotated {
                    @Override
                    public String toString() { return ""; }

                    @SuppressWarnings("unchecked")
                    public List<String> getItems() { return null; }

                    @Deprecated
                    public void old() {}
                }
                """);

        List<MethodInfo> results = parser.findMethods(file, "toString");
        assertEquals(1, results.size());
        assertTrue(results.getFirst().hasAnnotation("Override"));
        assertTrue(results.getFirst().annotations().getFirst().isMarker());

        results = parser.findMethods(file, "getItems");
        assertEquals(1, results.size());
        assertTrue(results.getFirst().hasAnnotation("SuppressWarnings"));
        assertEquals("\"unchecked\"",
                results.getFirst().annotations().getFirst().attributes().get("value"));

        results = parser.findMethods(file, "old");
        assertEquals(1, results.size());
        assertTrue(results.getFirst().hasAnnotation("Deprecated"));
    }

    // ---- enrichment: thrown exceptions ----

    @Test
    @DisplayName("Should extract thrown exceptions")
    void shouldExtractThrownExceptions() throws IOException {
        Path file = writeFile("Thrower.java", """
                import java.io.IOException;
                public class Thrower {
                    public void risky() throws IOException, IllegalStateException {
                        throw new IOException();
                    }
                }
                """);

        List<MethodInfo> results = parser.findMethods(file, "risky");
        assertEquals(1, results.size());

        MethodInfo m = results.getFirst();
        assertEquals(2, m.thrownExceptions().size());
        assertTrue(m.thrownExceptions().contains("IOException"));
        assertTrue(m.thrownExceptions().contains("IllegalStateException"));
    }

    // ---- enrichment: type parameters ----

    @Test
    @DisplayName("Should extract generic type parameters")
    void shouldExtractTypeParameters() throws IOException {
        Path file = writeFile("Generic.java", """
                import java.util.List;
                public class Generic {
                    public <T extends Comparable<T>> T max(T a, T b) {
                        return a.compareTo(b) > 0 ? a : b;
                    }
                }
                """);

        List<MethodInfo> results = parser.findMethods(file, "max");
        assertEquals(1, results.size());
        assertFalse(results.getFirst().typeParameters().isEmpty());
    }

    // ---- enrichment: javadoc ----

    @Test
    @DisplayName("Should extract javadoc")
    void shouldExtractJavadoc() throws IOException {
        Path file = writeFile("Documented.java", """
                public class Documented {
                    /**
                     * Does something important.
                     * @param x the input
                     * @return the output
                     */
                    public String process(String x) { return x; }
                }
                """);

        List<MethodInfo> results = parser.findMethods(file, "process");
        assertEquals(1, results.size());
        assertNotNull(results.getFirst().javadoc());
        assertTrue(results.getFirst().javadoc().contains("Does something important"));
    }

    // ---- findAllMethods ----

    @Test
    @DisplayName("findAllMethods should return rich info for all methods")
    void shouldFindAllMethodsWithRichInfo() throws IOException {
        Path file = writeFile("All.java", """
                public class All {
                    @Override
                    public String toString() { return ""; }

                    public void plain() {}

                    /** Doc */
                    public int calc(int x) { return x * 2; }
                }
                """);

        List<MethodInfo> all = parser.findAllMethods(file);
        assertEquals(3, all.size());

        MethodInfo overridden = all.stream()
                .filter(m -> m.name().equals("toString")).findFirst().orElseThrow();
        assertTrue(overridden.hasAnnotation("Override"));

        MethodInfo calc = all.stream()
                .filter(m -> m.name().equals("calc")).findFirst().orElseThrow();
        assertNotNull(calc.javadoc());
    }

    // ---- parseClasses ----

    @Test
    @DisplayName("Should parse classes with inheritance and interface info")
    void shouldParseClassesWithInheritance() throws IOException {
        Path file = writeFile("Hierarchy.java", """
                import java.io.Serializable;
                public abstract class Hierarchy extends Object implements Serializable {
                    public abstract void doWork();
                    public void idle() {}
                }
                """);

        List<ClassInfo> classes = parser.parseClasses(file);
        assertEquals(1, classes.size());

        ClassInfo ci = classes.getFirst();
        assertEquals("Hierarchy", ci.name());
        assertEquals("Object", ci.superClass());
        assertTrue(ci.interfaces().contains("Serializable"));
        assertTrue(ci.isAbstract());
        assertFalse(ci.isInterface());
        assertEquals(2, ci.methods().size());
    }

    @Test
    @DisplayName("Should parse interfaces")
    void shouldParseInterfaces() throws IOException {
        Path file = writeFile("Iface.java", """
                public interface Iface {
                    void doThing();
                    default int number() { return 42; }
                }
                """);

        List<ClassInfo> classes = parser.parseClasses(file);
        assertEquals(1, classes.size());

        ClassInfo ci = classes.getFirst();
        assertTrue(ci.isInterface());
        assertEquals(2, ci.methods().size());
    }

    @Test
    @DisplayName("Should parse class annotations")
    void shouldParseClassAnnotations() throws IOException {
        Path file = writeFile("Suppressed.java", """
                @SuppressWarnings("all")
                public class Suppressed {
                    public void work() {}
                }
                """);

        List<ClassInfo> classes = parser.parseClasses(file);
        assertFalse(classes.isEmpty());
        assertTrue(classes.getFirst().annotations().stream()
                .anyMatch(a -> a.name().equals("SuppressWarnings")));
    }

    // ---- findMethodsByAnnotation ----

    @Test
    @DisplayName("Should find methods by annotation name")
    void shouldFindMethodsByAnnotation() throws IOException {
        Path file = writeFile("ByAnnotation.java", """
                public class ByAnnotation {
                    @Override
                    public String toString() { return ""; }

                    public void noAnnotation() {}

                    @Deprecated
                    public void oldMethod() {}

                    @Override
                    public int hashCode() { return 0; }
                }
                """);

        List<MethodInfo> overrides = parser.findMethodsByAnnotation(file, "Override");
        assertEquals(2, overrides.size());
        assertTrue(overrides.stream().allMatch(m -> m.hasAnnotation("Override")));

        List<MethodInfo> deprecated = parser.findMethodsByAnnotation(file, "Deprecated");
        assertEquals(1, deprecated.size());
        assertEquals("oldMethod", deprecated.getFirst().name());
    }

    // ---- overloaded methods ----

    @Test
    @DisplayName("Should handle overloaded methods correctly")
    void shouldHandleOverloadedMethods() throws IOException {
        Path file = writeFile("Overloaded.java", """
                public class Overloaded {
                    public int add(int a, int b) { return a + b; }
                    public double add(double a, double b) { return a + b; }
                    public String add(String a, String b) { return a + b; }
                }
                """);

        List<MethodInfo> all = parser.findMethods(file, "add");
        assertEquals(3, all.size());

        List<MethodInfo> intVersion = parser.findMethods(file, "add", List.of("int", "int"));
        assertEquals(1, intVersion.size());
        assertEquals("int", intVersion.getFirst().returnType());

        List<MethodInfo> doubleVersion = parser.findMethods(file, "add", List.of("double", "double"));
        assertEquals(1, doubleVersion.size());
        assertEquals("double", doubleVersion.getFirst().returnType());
    }

    // ---- edge: nonexistent method ----

    @Test
    @DisplayName("Should return empty for nonexistent method")
    void shouldReturnEmptyForMissing() throws IOException {
        Path file = writeFile("Simple.java", """
                public class Simple { public void a() {} }
                """);
        assertTrue(parser.findMethods(file, "nonexistent").isEmpty());
    }

    // ---- fallback: TreeSitter if JavaParser fails ----

    @Test
    @DisplayName("Should fall back to TreeSitter for incomplete syntax")
    void shouldFallBackForBadSyntax() throws IOException {
        Path file = writeFile("Bad.java", """
                public class Bad {
                    public void okMethod() { System.out.println("ok"); }
                    public void broken() { // missing closing
                """);

        // JavaParser will fail, but TreeSitter should still find okMethod
        List<MethodInfo> results = parser.findAllMethods(file);
        assertFalse(results.isEmpty());
    }

    private Path writeFile(String name, String content) throws IOException {
        Path file = tempDir.resolve(name);
        Files.writeString(file, content);
        return file;
    }
}
