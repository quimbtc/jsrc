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

class FindCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void expandsSynonyms() throws Exception {
        var result = run("database errors",
                "public class DatabaseExceptionHandler {\n    public void handle() {}\n}",
                "public class OrderService {\n    public void process() {}\n}");
        @SuppressWarnings("unchecked")
        var matches = (List<Map<String, Object>>) result.get("matches");
        assertTrue(matches.stream().anyMatch(m -> m.get("name").toString().contains("DatabaseExceptionHandler")),
                "Should find via synonym expansion. Got: " + matches);
    }

    @Test
    void literalFallbackForUnknownWords() throws Exception {
        var result = run("OrderService",
                "public class OrderService {\n    public void process() {}\n}");
        @SuppressWarnings("unchecked")
        var matches = (List<Map<String, Object>>) result.get("matches");
        assertFalse(matches.isEmpty(), "Literal search should work");
    }

    @Test
    void filtersStopWords() throws Exception {
        var result = run("get the database errors",
                "public class DbErrorHandler {\n    public void handle() {}\n}");
        @SuppressWarnings("unchecked")
        var expanded = (List<String>) result.get("expanded");
        assertTrue(expanded.stream().noneMatch(e -> e.startsWith("the→")),
                "Should filter 'the'");
    }

    @Test
    void singleWord_works() throws Exception {
        var result = run("cache",
                "public class CacheManager {\n    public void evict() {}\n}");
        @SuppressWarnings("unchecked")
        var matches = (List<Map<String, Object>>) result.get("matches");
        assertFalse(matches.isEmpty());
    }

    @Test
    void noMatches_emptyList() throws Exception {
        var result = run("quantum computing",
                "public class OrderService {\n    public void process() {}\n}");
        @SuppressWarnings("unchecked")
        var matches = (List<Map<String, Object>>) result.get("matches");
        assertTrue(matches.isEmpty());
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
        new FindCommand(query).execute(ctx);
        return (Map<String, Object>) JsonReader.parse(baos.toString().trim());
    }
}
