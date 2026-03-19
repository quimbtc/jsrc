package com.jsrc.app.command;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.jsrc.app.index.CodebaseIndex;
import com.jsrc.app.index.IndexedCodebase;
import com.jsrc.app.output.JsonFormatter;
import com.jsrc.app.output.JsonReader;
import com.jsrc.app.parser.HybridJavaParser;

class SnippetCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void servicePattern_extractsTemplate() throws Exception {
        var result = run("service", """
                import org.slf4j.Logger;
                public class OrderService {
                    private final OrderRepo repo;
                    public OrderService(OrderRepo repo) {
                        this.repo = repo;
                    }
                    public void process() {}
                    public void cancel() {}
                }
                """, """
                public class OrderRepo { public void save() {} }
                """);
        assertEquals("service", result.get("pattern"));
        String template = (String) result.get("template");
        assertTrue(template.contains("${Name}Service"), "Template should have placeholder. Got: " + template);
        assertNotNull(result.get("basedOn"));
        assertNotNull(result.get("imports"));
    }

    @Test
    void noMatchingPattern_errorWithAvailable() throws Exception {
        var result = run("xyz", """
                public class Plain { public void run() {} }
                """);
        assertNotNull(result.get("error"));
        assertNotNull(result.get("availablePatterns"));
    }

    @Test
    void templateHasPlaceholders() throws Exception {
        var result = run("service", """
                public class UserService {
                    private final UserRepo repo;
                    public UserService(UserRepo repo) { this.repo = repo; }
                    public void create() {}
                }
                """, """
                public class UserRepo { public void save() {} }
                """);
        String template = (String) result.get("template");
        assertTrue(template.contains("${Name}"), "Should have ${Name}");
    }

    @Test
    void basedOnPointsToRealClass() throws Exception {
        var result = run("service", """
                public class PaymentService {
                    public void charge() {}
                }
                """);
        String basedOn = (String) result.get("basedOn");
        assertTrue(basedOn.contains("PaymentService"));
    }

    @Test
    void importsFromRealFile() throws Exception {
        var result = run("service", """
                import java.util.List;
                import org.slf4j.Logger;
                public class DataService {
                    public List<String> getAll() { return List.of(); }
                }
                """);
        @SuppressWarnings("unchecked")
        var imports = (List<String>) result.get("imports");
        assertTrue(imports.stream().anyMatch(i -> i.contains("slf4j")),
                "Should include SLF4J import. Got: " + imports);
    }

    @Test
    void conventionsListNotEmpty() throws Exception {
        var result = run("service", """
                public class SvcService {
                    private final Repo repo;
                    public SvcService(Repo repo) { this.repo = repo; }
                    public void run() {}
                }
                """);
        @SuppressWarnings("unchecked")
        var conventions = (List<String>) result.get("conventions");
        assertNotNull(conventions);
        // Should detect constructor injection
        assertTrue(conventions.stream().anyMatch(c -> c.toLowerCase().contains("constructor")),
                "Should detect constructor injection. Got: " + conventions);
    }

    // --- helpers ---

    @SuppressWarnings("unchecked")
    private Map<String, Object> run(String pattern, String... sources) throws Exception {
        List<Path> files = new java.util.ArrayList<>();
        for (int i = 0; i < sources.length; i++) {
            String src = sources[i];
            String className = "Class" + i;
            int idx = src.indexOf("class ");
            if (idx >= 0) className = src.substring(idx + 6).trim().split("[\\s{<]")[0];
            Path file = tempDir.resolve(className + ".java");
            Files.writeString(file, src);
            files.add(file);
        }
        var parser = new HybridJavaParser();
        var index = new CodebaseIndex();
        index.build(parser, files, tempDir, List.of());
        index.save(tempDir);
        var indexed = IndexedCodebase.tryLoad(tempDir, files);

        var baos = new ByteArrayOutputStream();
        var ctx = new CommandContext(files, tempDir.toString(), null,
                new JsonFormatter(false, null, new PrintStream(baos)), indexed, parser);
        new SnippetCommand(pattern).execute(ctx);
        return (Map<String, Object>) JsonReader.parse(baos.toString().trim());
    }
}
