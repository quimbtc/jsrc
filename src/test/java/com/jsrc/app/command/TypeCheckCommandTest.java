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

class TypeCheckCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void methodExists_returnsType() throws Exception {
        var result = run("Svc.getName", """
                public class Svc {
                    public String getName() { return "test"; }
                }
                """);
        assertEquals(true, result.get("valid"));
        assertEquals("String", result.get("returnType"));
    }

    @Test
    void voidMethod_warnsAboutAssignment() throws Exception {
        var result = run("Svc.run", """
                public class Svc {
                    public void run() {}
                }
                """);
        assertEquals(true, result.get("valid"));
        assertNotNull(result.get("warning"), "Should warn about void return");
    }

    @Test
    void methodNotFound_invalid() throws Exception {
        var result = run("Svc.ghost", """
                public class Svc {
                    public void run() {}
                }
                """);
        assertEquals(false, result.get("valid"));
    }

    @Test
    void includesSignature() throws Exception {
        var result = run("Svc.process", """
                public class Svc {
                    public int process(String input) { return 0; }
                }
                """);
        assertNotNull(result.get("signature"));
    }

    @Test
    void methodNotFound_withSuggestion() throws Exception {
        var result = run("Svc.proces", """
                public class Svc {
                    public void process() {}
                }
                """);
        assertEquals(false, result.get("valid"));
        // Should suggest closest method
        assertNotNull(result.get("closest"), "Should suggest closest method. Got: " + result);
    }

    @Test
    void primitiveReturnType_identified() throws Exception {
        var result = run("Svc.getCount", """
                public class Svc {
                    public int getCount() { return 0; }
                }
                """);
        assertEquals(true, result.get("valid"));
        assertEquals("int", result.get("returnType"));
    }

    @Test
    void booleanReturnType_noVoidWarning() throws Exception {
        var result = run("Svc.isActive", """
                public class Svc {
                    public boolean isActive() { return true; }
                }
                """);
        assertEquals(true, result.get("valid"));
        assertNull(result.get("warning"), "Non-void should not have warning");
    }

    // --- helpers ---

    @SuppressWarnings("unchecked")
    private Map<String, Object> run(String expr, String... sources) throws Exception {
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

        new TypeCheckCommand(expr).execute(ctx);
        String json = baos.toString().trim();
        return (Map<String, Object>) JsonReader.parse(json);
    }
}
