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

class ScopeCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void matchesByClassName() throws Exception {
        var result = run("Binder", """
                public class Binder {
                    public void bind() {}
                }
                """, """
                public class Other {
                    public void other() {}
                }
                """);
        @SuppressWarnings("unchecked")
        var matched = (List<Map<String, Object>>) result.get("matchedClasses");
        assertTrue(matched.stream().anyMatch(m -> ((String) m.get("name")).contains("Binder")));
    }

    @Test
    void multipleKeywords() throws Exception {
        var result = run("validation binding", """
                public class BindValidator {
                    public void validateBinding() {}
                }
                """, """
                public class Other {
                    public void other() {}
                }
                """);
        @SuppressWarnings("unchecked")
        var matched = (List<Map<String, Object>>) result.get("matchedClasses");
        assertFalse(matched.isEmpty());
        // BindValidator should score high (matches both keywords)
        assertEquals("BindValidator", ((String) matched.getFirst().get("name")).replace(".", ""));
    }

    @Test
    void noMatches_emptyWithMessage() throws Exception {
        var result = run("xyznonexistent", """
                public class Real {
                    public void method() {}
                }
                """);
        @SuppressWarnings("unchecked")
        var matched = (List<?>) result.get("matchedClasses");
        assertTrue(matched.isEmpty());
        assertNotNull(result.get("message"));
    }

    @Test
    void caseInsensitive() throws Exception {
        var result = run("ORDER", """
                public class OrderService {
                    public void processOrder() {}
                }
                """);
        @SuppressWarnings("unchecked")
        var matched = (List<?>) result.get("matchedClasses");
        assertFalse(matched.isEmpty(), "Should match case-insensitive");
    }

    @Test
    void topMethodsIncluded() throws Exception {
        var result = run("process", """
                public class Processor {
                    public void processData() {}
                    public void processFile() {}
                    public void other() {}
                }
                """);
        @SuppressWarnings("unchecked")
        var methods = (List<String>) result.get("topMethods");
        assertTrue(methods.stream().anyMatch(m -> m.contains("processData")));
        assertTrue(methods.stream().anyMatch(m -> m.contains("processFile")));
        assertFalse(methods.stream().anyMatch(m -> m.contains("other")));
    }

    @Test
    void estimatedTokensReasonable() throws Exception {
        var result = run("test", """
                public class TestClass {
                    public void testMethod() {}
                }
                """);
        int tokens = ((Number) result.get("estimatedTokens")).intValue();
        assertTrue(tokens > 0, "Should estimate some tokens");
    }

    // --- helpers ---

    @SuppressWarnings("unchecked")
    private Map<String, Object> run(String query, String... sources) throws Exception {
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

        new ScopeCommand(query).execute(ctx);
        String json = baos.toString().trim();
        return (Map<String, Object>) JsonReader.parse(json);
    }
}
