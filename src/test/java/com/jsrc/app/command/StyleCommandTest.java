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

class StyleCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void outputUnder300Chars() throws Exception {
        var result = runRaw("""
                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;
                public class Svc {
                    private static final Logger log = LoggerFactory.getLogger(Svc.class);
                    private final Repo repo;
                    public Svc(Repo repo) { this.repo = repo; }
                    public void run() {}
                }
                """);
        assertTrue(result.length() < 300,
                "Output should be <300 chars. Got: " + result.length() + " → " + result);
    }

    @Test
    void detectsSlf4j() throws Exception {
        var result = run("""
                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;
                public class A {
                    private static final Logger log = LoggerFactory.getLogger(A.class);
                }
                """);
        assertEquals("SLF4J", result.get("logging"));
    }

    @Test
    void detectsConstructorInjection() throws Exception {
        var result = run("""
                public class Svc {
                    private final Repo repo;
                    private final Bus bus;
                    public Svc(Repo repo, Bus bus) {
                        this.repo = repo;
                        this.bus = bus;
                    }
                }
                """);
        assertEquals("constructor", result.get("injection"));
    }

    @Test
    void detectsJavaVersion() throws Exception {
        // Write .jsrc.yaml with javaVersion
        Files.writeString(tempDir.resolve(".jsrc.yaml"), "javaVersion: \"21\"");
        var result = run("""
                public class A { public void run() {} }
                """);
        assertEquals("21", result.get("java"));
    }

    @Test
    void allFieldsPresent() throws Exception {
        var result = run("""
                public class A { public void run() {} }
                """);
        assertNotNull(result.get("logging"));
        assertNotNull(result.get("injection"));
        assertNotNull(result.get("java"));
    }

    @Test
    void noLogging_reportsNone() throws Exception {
        var result = run("""
                public class Plain {
                    public void work() {}
                }
                """);
        // Should not crash, report "none" or similar
        String logging = (String) result.get("logging");
        assertNotNull(logging);
    }

    // --- helpers ---

    @SuppressWarnings("unchecked")
    private Map<String, Object> run(String... sources) throws Exception {
        String raw = runRaw(sources);
        return (Map<String, Object>) JsonReader.parse(raw);
    }

    private String runRaw(String... sources) throws Exception {
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
        var config = com.jsrc.app.config.ProjectConfig.load(tempDir).orElse(null);

        var baos = new ByteArrayOutputStream();
        var ctx = new CommandContext(files, tempDir.toString(), config,
                new JsonFormatter(false, null, new PrintStream(baos)), indexed, parser);

        new StyleCommand().execute(ctx);
        return baos.toString().trim();
    }
}
