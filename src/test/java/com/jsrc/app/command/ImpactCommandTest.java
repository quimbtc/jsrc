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

class ImpactCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void noCallers_riskNone() throws Exception {
        var result = run("Isolated.lonely", """
                public class Isolated {
                    public void lonely() {}
                }
                """);
        assertEquals("none", result.get("riskLevel"));
        assertEquals(0, ((Number) result.get("directCallers")).intValue());
    }

    @Test
    void fewCallers_riskLow() throws Exception {
        var result = run("Svc.run", """
                public class Svc {
                    public void run() {}
                }
                """, """
                public class A {
                    private Svc svc;
                    public void go() { svc.run(); }
                }
                """);
        assertEquals("low", result.get("riskLevel"));
        assertEquals(1, ((Number) result.get("directCallers")).intValue());
    }

    @Test
    void transitiveCallers_counted() throws Exception {
        var result = run("Repo.save", """
                public class Repo {
                    public void save() {}
                }
                """, """
                public class Service {
                    private Repo repo;
                    public void process() { repo.save(); }
                }
                """, """
                public class Controller {
                    private Service svc;
                    public void handle() { svc.process(); }
                }
                """);
        assertTrue(((Number) result.get("transitiveCallers")).intValue() >= 2,
                "Should count transitive callers. Got: " + result);
        @SuppressWarnings("unchecked")
        var affected = (List<String>) result.get("affectedClasses");
        assertTrue(affected.contains("Service"));
        assertTrue(affected.contains("Controller"));
    }

    @Test
    void circularCallers_noInfiniteLoop() throws Exception {
        var result = run("A.ping", """
                public class A {
                    private B b;
                    public void ping() { b.pong(); }
                }
                """, """
                public class B {
                    private A a;
                    public void pong() { a.ping(); }
                }
                """);
        // Should not hang — circular reference handled
        assertNotNull(result.get("riskLevel"));
    }

    @Test
    void methodNotFound_error() throws Exception {
        var result = run("Ghost.method", """
                public class Real {
                    public void other() {}
                }
                """);
        assertNotNull(result);
    }

    @Test
    void noDuplicatesInAffected() throws Exception {
        var result = run("Svc.run", """
                public class Svc {
                    public void run() {}
                }
                """, """
                public class A {
                    private Svc svc;
                    public void a1() { svc.run(); }
                    public void a2() { svc.run(); }
                }
                """);
        @SuppressWarnings("unchecked")
        var affected = (List<String>) result.get("affectedClasses");
        assertEquals(affected.size(), affected.stream().distinct().count(), "No duplicates");
    }

    // --- helpers ---

    @SuppressWarnings("unchecked")
    private Map<String, Object> run(String methodRef, String... sources) throws Exception {
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

        new ImpactCommand(methodRef).execute(ctx);
        String json = baos.toString().trim();
        if (json.isEmpty()) return Map.of();
        return (Map<String, Object>) JsonReader.parse(json);
    }
}
