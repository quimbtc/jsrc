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

class ContextForCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void fixIntent_withClassMethod() throws Exception {
        var result = run("fix NPE in OrderService.validate");
        assertEquals("fix", result.get("intent"));
        assertEquals("OrderService", result.get("detectedClass"));
        assertEquals("OrderService.validate", result.get("detectedMethod"));
        @SuppressWarnings("unchecked")
        var plan = (List<Map<String, Object>>) result.get("readPlan");
        assertTrue(plan.stream().anyMatch(s -> s.get("command").toString().contains("--read")));
        assertTrue(plan.stream().anyMatch(s -> s.get("command").toString().contains("--impact")));
    }

    @Test
    void featureIntent() throws Exception {
        var result = run("add support for YAML profiles");
        assertEquals("feature", result.get("intent"));
        @SuppressWarnings("unchecked")
        var plan = (List<Map<String, Object>>) result.get("readPlan");
        assertTrue(plan.stream().anyMatch(s -> s.get("command").toString().contains("--scope")));
        assertTrue(plan.stream().anyMatch(s -> s.get("command").toString().contains("--style")));
    }

    @Test
    void exploreIntent_default() throws Exception {
        var result = run("how does the logging system work");
        assertEquals("explore", result.get("intent"));
        @SuppressWarnings("unchecked")
        var plan = (List<Map<String, Object>>) result.get("readPlan");
        assertTrue(plan.stream().anyMatch(s -> s.get("command").toString().contains("--overview")
                || s.get("command").toString().contains("--scope")));
    }

    @Test
    void changeIntent() throws Exception {
        var result = run("rename Binder.bind to Binder.bindProperty");
        assertEquals("change", result.get("intent"));
        @SuppressWarnings("unchecked")
        var plan = (List<Map<String, Object>>) result.get("readPlan");
        assertTrue(plan.stream().anyMatch(s -> s.get("command").toString().contains("--impact")
                || s.get("command").toString().contains("--callers")));
    }

    @Test
    void hasBudgetRemaining() throws Exception {
        var result = run("fix bug in OrderService.process");
        int remaining = ((Number) result.get("budgetRemaining")).intValue();
        int total = ((Number) result.get("totalEstTokens")).intValue();
        assertTrue(remaining >= 0, "Budget remaining should be ≥ 0");
        assertTrue(total > 0, "Should have some estimated tokens");
        assertEquals(2000, remaining + total, "Budget should add up to default 2000");
    }

    @Test
    void emptyInput_error() throws Exception {
        var result = run("");
        assertNotNull(result.get("error"));
    }

    @Test
    void unknownIntent_fallsBackToExplore() throws Exception {
        var result = run("deploy to production server");
        assertEquals("explore", result.get("intent"));
    }

    // --- helpers ---

    @SuppressWarnings("unchecked")
    private Map<String, Object> run(String task) throws Exception {
        Path file = tempDir.resolve("OrderService.java");
        Files.writeString(file, "public class OrderService {\n    public void validate() {}\n    public void process() {}\n}");
        var files = List.of(file);
        var parser = new HybridJavaParser();
        var index = new CodebaseIndex();
        index.build(parser, files, tempDir, List.of());
        index.save(tempDir);
        var indexed = IndexedCodebase.tryLoad(tempDir, files);

        var baos = new ByteArrayOutputStream();
        var ctx = new CommandContext(files, tempDir.toString(), null,
                new JsonFormatter(false, null, new PrintStream(baos)), indexed, parser);
        new ContextForCommand(task).execute(ctx);
        return (Map<String, Object>) JsonReader.parse(baos.toString().trim());
    }
}
