package com.jsrc.app.spec;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.jsrc.app.output.MarkdownFormatter;
import com.jsrc.app.parser.ContextAssembler;
import com.jsrc.app.parser.HybridJavaParser;
import com.jsrc.app.parser.model.ClassInfo;

class SpecRoundtripTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Full roundtrip: assemble → toMarkdown → parse → verify = pass")
    void fullRoundtrip() throws IOException {
        Path javaFile = writeFile("Calculator.java", """
                package com.test;
                public class Calculator {
                    /**
                     * Adds two numbers.
                     */
                    public int add(int a, int b) {
                        return a + b;
                    }
                    public int subtract(int a, int b) {
                        return a - b;
                    }
                }
                """);

        var parser = new HybridJavaParser();
        List<ClassInfo> allClasses = parser.parseClasses(javaFile);

        // Assemble context
        var assembler = new ContextAssembler(parser);
        Map<String, Object> ctx = assembler.assemble(
                List.of(javaFile), "Calculator", allClasses, null);
        assertNotNull(ctx);

        // Generate Markdown
        String markdown = MarkdownFormatter.toMarkdown(ctx);
        assertNotNull(markdown);
        assertTrue(markdown.contains("# Calculator"));
        assertTrue(markdown.contains("add"));
        assertTrue(markdown.contains("subtract"));

        // Save and parse spec
        Path specFile = tempDir.resolve("Calculator.md");
        Files.writeString(specFile, markdown);
        SpecParser.Spec spec = SpecParser.parse(specFile);
        assertEquals("Calculator", spec.className());
        assertEquals(2, spec.methods().size());

        // Verify
        ClassInfo impl = allClasses.getFirst();
        Map<String, Object> result = SpecVerifier.verify(impl, spec);
        assertEquals(Boolean.TRUE, result.get("pass"));
        @SuppressWarnings("unchecked")
        List<?> discs = (List<?>) result.get("discrepancies");
        assertTrue(discs.isEmpty(), "Roundtrip should produce 0 discrepancies");
    }

    @Test
    @DisplayName("Verify should detect missing method")
    void shouldDetectMissingMethod() throws IOException {
        Path javaFile = writeFile("Service.java", """
                public class Service {
                    public void process() {}
                }
                """);

        // Spec expects 'process' and 'validate', but impl only has 'process'
        Path specFile = tempDir.resolve("Service.md");
        Files.writeString(specFile, """
                # Service
                ## Methods
                ### public void process()
                ### public boolean validate()
                """);

        var parser = new HybridJavaParser();
        ClassInfo impl = parser.parseClasses(javaFile).getFirst();
        SpecParser.Spec spec = SpecParser.parse(specFile);

        Map<String, Object> result = SpecVerifier.verify(impl, spec);
        assertEquals(Boolean.FALSE, result.get("pass"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> discs = (List<Map<String, Object>>) result.get("discrepancies");
        assertTrue(discs.stream().anyMatch(d ->
                "missing_method".equals(d.get("type")) && "validate".equals(d.get("method"))));
    }

    @Test
    @DisplayName("Verify should detect undocumented method")
    void shouldDetectUndocumentedMethod() throws IOException {
        Path javaFile = writeFile("Worker.java", """
                public class Worker {
                    public void run() {}
                    public void cleanup() {}
                }
                """);

        // Spec only knows about 'run'
        Path specFile = tempDir.resolve("Worker.md");
        Files.writeString(specFile, """
                # Worker
                ## Methods
                ### public void run()
                """);

        var parser = new HybridJavaParser();
        ClassInfo impl = parser.parseClasses(javaFile).getFirst();
        SpecParser.Spec spec = SpecParser.parse(specFile);

        Map<String, Object> result = SpecVerifier.verify(impl, spec);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> discs = (List<Map<String, Object>>) result.get("discrepancies");
        assertTrue(discs.stream().anyMatch(d ->
                "undocumented_method".equals(d.get("type")) && "cleanup".equals(d.get("method"))));
    }

    @Test
    @DisplayName("Verify should detect missing annotation")
    void shouldDetectMissingAnnotation() throws IOException {
        Path javaFile = writeFile("Handler.java", """
                public class Handler {
                    public void handle() {}
                }
                """);

        Path specFile = tempDir.resolve("Handler.md");
        Files.writeString(specFile, """
                # Handler
                ## Methods
                ### public void handle()
                - **Annotation:** @Override
                """);

        var parser = new HybridJavaParser();
        ClassInfo impl = parser.parseClasses(javaFile).getFirst();
        SpecParser.Spec spec = SpecParser.parse(specFile);

        Map<String, Object> result = SpecVerifier.verify(impl, spec);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> discs = (List<Map<String, Object>>) result.get("discrepancies");
        assertTrue(discs.stream().anyMatch(d ->
                "missing_annotation".equals(d.get("type"))));
    }

    @Test
    @DisplayName("SpecParser should parse throws from spec")
    void shouldParseThrows() throws IOException {
        Path specFile = tempDir.resolve("Risky.md");
        Files.writeString(specFile, """
                # Risky
                ## Methods
                ### public void execute()
                - **Throws:** IOException, IllegalStateException
                """);

        SpecParser.Spec spec = SpecParser.parse(specFile);
        assertEquals(1, spec.methods().size());
        var method = spec.methods().getFirst();
        assertEquals(2, method.expectedThrows().size());
        assertTrue(method.expectedThrows().contains("IOException"));
        assertTrue(method.expectedThrows().contains("IllegalStateException"));
    }

    private Path writeFile(String name, String content) throws IOException {
        Path file = tempDir.resolve(name);
        Files.writeString(file, content);
        return file;
    }
}
