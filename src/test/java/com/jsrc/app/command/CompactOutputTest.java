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

/**
 * Tests that compact output (default) is significantly smaller than full output.
 * Compact mode trims large arrays and verbose details. --full restores everything.
 */
class CompactOutputTest {

    @TempDir
    Path tempDir;

    @Test
    void overview_compact_omitsPackageList() throws Exception {
        var result = runOverview(false);
        // Compact: should have stats but no package list
        assertTrue(result.containsKey("totalFiles"));
        assertTrue(result.containsKey("totalClasses"));
        assertTrue(result.containsKey("totalPackages"));
        assertFalse(result.containsKey("packages"),
                "Compact overview should NOT include packages array");
    }

    @Test
    void overview_full_includesPackageList() throws Exception {
        var result = runOverview(true);
        assertTrue(result.containsKey("packages"),
                "Full overview SHOULD include packages array");
    }

    @Test
    void summary_compact_limitsMethodCount() throws Exception {
        var result = runSummary(false);
        @SuppressWarnings("unchecked")
        var methods = (List<Object>) result.get("methods");
        assertTrue(methods.size() <= 20,
                "Compact summary should show max 20 methods, got: " + methods.size());
    }

    @Test
    void summary_full_includesAllMethods() throws Exception {
        var result = runSummary(true);
        @SuppressWarnings("unchecked")
        var methods = (List<Object>) result.get("methods");
        assertTrue(methods.size() > 20,
                "Full summary should show all methods, got: " + methods.size());
    }

    @Test
    void compact_isDefault() throws Exception {
        // When fullOutput is not set (default constructor), should be compact
        var result = runOverview(false);
        assertFalse(result.containsKey("packages"),
                "Default should be compact (no packages)");
    }

    @Test
    void classes_compact_belowThreshold_noTruncation() throws Exception {
        // 30 classes < 50 threshold → no truncation, show all
        var files = generateManyClasses();
        var ctx = buildContext(false, files);
        var baos = new ByteArrayOutputStream();
        var ctxCompact = new CommandContext(ctx.javaFiles(), ctx.rootPath(), null,
                new JsonFormatter(false, null, new PrintStream(baos)),
                ctx.indexed(), ctx.parser(), false, null, false);

        new ClassesCommand().execute(ctxCompact);
        String json = baos.toString().trim();
        // Below 50 → full output, no "truncated" key
        assertFalse(json.contains("truncated"),
                "30 classes should not be truncated");
    }

    @Test
    void endpoints_compact_belowThreshold_noTruncation() throws Exception {
        // Project with few endpoints → no truncation
        var files = generateManyClasses(); // no REST annotations → 0 endpoints
        var ctx = buildContext(false, files);
        var baos = new ByteArrayOutputStream();
        var ctxCompact = new CommandContext(ctx.javaFiles(), ctx.rootPath(), null,
                new JsonFormatter(false, null, new PrintStream(baos)),
                ctx.indexed(), ctx.parser(), false, null, false);

        new EndpointsCommand().execute(ctxCompact);
        String json = baos.toString().trim();
        assertFalse(json.contains("truncated"),
                "Few endpoints should not be truncated");
    }

    @Test
    void summary_compact_smallClass_allMethods() throws Exception {
        // Class with 5 methods (< 20 threshold) → all methods shown
        List<Path> files = new java.util.ArrayList<>();
        StringBuilder sb = new StringBuilder();
        sb.append("package com.example;\npublic class SmallService {\n");
        for (int i = 0; i < 5; i++) {
            sb.append("    public void m").append(i).append("() {}\n");
        }
        sb.append("}\n");
        Path file = tempDir.resolve("SmallService.java");
        Files.writeString(file, sb.toString());
        files.add(file);

        var ctx = buildContext(false, files);
        var baos = new ByteArrayOutputStream();
        var ctxCompact = new CommandContext(ctx.javaFiles(), ctx.rootPath(), null,
                new JsonFormatter(false, null, new PrintStream(baos)),
                ctx.indexed(), ctx.parser(), false, null, false);

        new SummaryCommand("SmallService").execute(ctxCompact);
        @SuppressWarnings("unchecked")
        var result = (Map<String, Object>) JsonReader.parse(baos.toString().trim());
        @SuppressWarnings("unchecked")
        var methods = (List<Object>) result.get("methods");
        assertEquals(5, methods.size(), "Small class should show all 5 methods");
    }

    @Test
    void overview_full_backwardCompat() throws Exception {
        // --full produces same structure as before (with packages array)
        var result = runOverview(true);
        assertTrue(result.containsKey("totalFiles"));
        assertTrue(result.containsKey("totalClasses"));
        assertTrue(result.containsKey("totalPackages"));
        assertTrue(result.containsKey("packages"), "--full must include packages");
        @SuppressWarnings("unchecked")
        var packages = (List<Object>) result.get("packages");
        assertTrue(packages.size() > 0, "--full packages should not be empty");
    }

    @Test
    void classes_compact_hintPresent() throws Exception {
        // Generate 60 classes to trigger truncation, verify hint field
        List<Path> files = new java.util.ArrayList<>();
        for (int i = 0; i < 60; i++) {
            String pkg = "com.example.pkg" + (i % 10);
            String name = "Cls" + i;
            Path file = tempDir.resolve(name + ".java");
            Files.writeString(file, "package " + pkg + ";\npublic class " + name + " {}\n");
            files.add(file);
        }

        var ctx = buildContext(false, files);
        var baos = new ByteArrayOutputStream();
        var ctxCompact = new CommandContext(ctx.javaFiles(), ctx.rootPath(), null,
                new JsonFormatter(false, null, new PrintStream(baos)),
                ctx.indexed(), ctx.parser(), false, null, false);

        new ClassesCommand().execute(ctxCompact);
        @SuppressWarnings("unchecked")
        var result = (Map<String, Object>) JsonReader.parse(baos.toString().trim());
        assertTrue(result.containsKey("hint"), "Truncated output should have hint");
        assertTrue(result.containsKey("truncated"), "Should have truncated flag");
        assertEquals(60L, ((Number) result.get("total")).longValue());
    }

    // --- helpers ---

    @SuppressWarnings("unchecked")
    private Map<String, Object> runOverview(boolean full) throws Exception {
        var ctx = buildContext(full, generateManyClasses());
        var baos = new ByteArrayOutputStream();
        var ctxWithOutput = new CommandContext(ctx.javaFiles(), ctx.rootPath(), null,
                new JsonFormatter(false, null, new PrintStream(baos)),
                ctx.indexed(), ctx.parser(), false, null, full);

        new OverviewCommand().execute(ctxWithOutput);
        String json = baos.toString().trim();
        return (Map<String, Object>) JsonReader.parse(json);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> runSummary(boolean full) throws Exception {
        var ctx = buildContext(full, generateBigClass());
        var baos = new ByteArrayOutputStream();
        var ctxWithOutput = new CommandContext(ctx.javaFiles(), ctx.rootPath(), null,
                new JsonFormatter(false, null, new PrintStream(baos)),
                ctx.indexed(), ctx.parser(), false, null, full);

        new SummaryCommand("BigService").execute(ctxWithOutput);
        String json = baos.toString().trim();
        return (Map<String, Object>) JsonReader.parse(json);
    }

    private CommandContext buildContext(boolean full, List<Path> files) throws Exception {
        var parser = new HybridJavaParser();
        var index = new CodebaseIndex();
        index.build(parser, files, tempDir, List.of());
        index.save(tempDir);
        var indexed = IndexedCodebase.tryLoad(tempDir, files);

        return new CommandContext(files, tempDir.toString(), null,
                new JsonFormatter(false, null, new PrintStream(new ByteArrayOutputStream())),
                indexed, parser, false, null, full);
    }

    private List<Path> generateManyClasses() throws Exception {
        List<Path> files = new java.util.ArrayList<>();
        // Generate 30 classes across 10 packages to ensure packages array is non-trivial
        for (int i = 0; i < 30; i++) {
            String pkg = "com.example.pkg" + (i % 10);
            String name = "Class" + i;
            Path file = tempDir.resolve(name + ".java");
            Files.writeString(file, "package " + pkg + ";\npublic class " + name + " {\n"
                    + "    public void method" + i + "() {}\n}\n");
            files.add(file);
        }
        return files;
    }

    private List<Path> generateBigClass() throws Exception {
        List<Path> files = new java.util.ArrayList<>();
        StringBuilder sb = new StringBuilder();
        sb.append("package com.example;\npublic class BigService {\n");
        for (int i = 0; i < 30; i++) {
            sb.append("    public void method").append(i).append("() {}\n");
        }
        sb.append("}\n");
        Path file = tempDir.resolve("BigService.java");
        Files.writeString(file, sb.toString());
        files.add(file);
        return files;
    }
}
