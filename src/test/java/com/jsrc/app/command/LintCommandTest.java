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

class LintCommandTest {

    @TempDir Path tempDir;

    @Test
    void unknownType_warns() throws Exception {
        var diags = run("Svc", "public class Svc {\n    private FakeRepo repo;\n    public void run() {}\n}");
        assertTrue(diags.stream().anyMatch(d -> d.get("message").toString().contains("FakeRepo")),
                "Should warn about unknown type. Got: " + diags);
    }

    @Test
    void jdkType_noWarning() throws Exception {
        var diags = run("Svc", "public class Svc {\n    private String name;\n    private List<String> items;\n    public void run() {}\n}");
        assertTrue(diags.stream().noneMatch(d -> d.get("message").toString().contains("String")),
                "Should not warn about JDK types");
        assertTrue(diags.stream().noneMatch(d -> d.get("message").toString().contains("List")),
                "Should not warn about List");
    }

    @Test
    void primitive_noWarning() throws Exception {
        var diags = run("Svc", "public class Svc {\n    private int count;\n    private boolean active;\n    public void run() {}\n}");
        assertTrue(diags.stream().noneMatch(d -> d.get("message").toString().contains("int")
                        || d.get("message").toString().contains("boolean")),
                "Should not warn about primitives. Got: " + diags);
    }

    @Test
    void constructor_notReportedAsDeadCode() throws Exception {
        var diags = run("Svc", "public class Svc {\n    public Svc() {}\n    public void run() {}\n}");
        assertTrue(diags.stream().noneMatch(d -> d.get("message").toString().contains("Svc()")),
                "Constructor should not be reported as dead code");
    }

    @Test
    void toStringEquals_skipped() throws Exception {
        var diags = run("Svc", """
                public class Svc {
                    public String toString() { return ""; }
                    public int hashCode() { return 0; }
                    public boolean equals(Object o) { return false; }
                    public void run() {}
                }
                """);
        assertTrue(diags.stream().noneMatch(d ->
                        d.get("message").toString().contains("toString")
                                || d.get("message").toString().contains("hashCode")
                                || d.get("message").toString().contains("equals")),
                "Object methods should be skipped");
    }

    @Test
    void knownType_noWarning() throws Exception {
        // Type exists in the codebase index
        var diags = run("Controller",
                "public class Controller {\n    private Repo repo;\n    public void handle() {}\n}",
                "public class Repo {\n    public void save() {}\n}");
        assertTrue(diags.stream().noneMatch(d -> d.get("message").toString().contains("Repo")),
                "Known codebase type should not warn");
    }

    @Test
    void classNotFound_error() throws Exception {
        List<Path> files = List.of();
        var parser = new HybridJavaParser();
        var baos = new ByteArrayOutputStream();
        var ctx = new CommandContext(files, tempDir.toString(), null,
                new JsonFormatter(false, null, new PrintStream(baos)), null, parser);
        new LintCommand("Ghost").execute(ctx);
        @SuppressWarnings("unchecked")
        var result = (Map<String, Object>) JsonReader.parse(baos.toString().trim());
        assertNotNull(result.get("error"));
    }

    // --- helpers ---
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> run(String target, String... sources) throws Exception {
        List<Path> files = new java.util.ArrayList<>();
        for (int i = 0; i < sources.length; i++) {
            String src = sources[i]; String cn = "C" + i;
            int idx = src.indexOf("class "); if (idx >= 0) cn = src.substring(idx + 6).trim().split("[\\s{<]")[0];
            Path f = tempDir.resolve(cn + ".java"); Files.writeString(f, src); files.add(f);
        }
        var parser = new HybridJavaParser(); var index = new CodebaseIndex();
        index.build(parser, files, tempDir, List.of()); index.save(tempDir);
        var indexed = IndexedCodebase.tryLoad(tempDir, files);
        var baos = new ByteArrayOutputStream();
        var ctx = new CommandContext(files, tempDir.toString(), null, new JsonFormatter(false, null, new PrintStream(baos)), indexed, parser);
        new LintCommand(target).execute(ctx);
        Object parsed = JsonReader.parse(baos.toString().trim());
        if (parsed instanceof Map<?,?> map) {
            return (List<Map<String, Object>>) map.get("diagnostics");
        }
        return (List<Map<String, Object>>) parsed;
    }
}
