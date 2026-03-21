package com.jsrc.app.command;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.jsrc.app.index.IndexedCodebase;
import com.jsrc.app.output.OutputFormatter;
import com.jsrc.app.parser.HybridJavaParser;

/**
 * Regression tests for bugs fixed on 2026-03-21.
 * Each test verifies a specific bugfix to prevent regression.
 */
class BugfixRegressionTest {

    @TempDir
    static Path tempDir;
    static List<Path> javaFiles;
    static HybridJavaParser parser;

    @BeforeAll
    static void setUp() throws Exception {
        parser = new HybridJavaParser();

        // Create a mini codebase with caller/callee relationships
        Path srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir);

        Files.writeString(srcDir.resolve("Service.java"),
                """
                package com.example;
                public class Service {
                    public void process() {
                        Helper h = new Helper();
                        h.doWork();
                        h.compute();
                    }
                    public void other() {}
                }
                """);

        Files.writeString(srcDir.resolve("Helper.java"),
                """
                package com.example;
                public class Helper {
                    public void doWork() {
                        // TODO implement this
                        // FIXME broken edge case
                    }
                    public void compute() {}
                }
                """);

        Files.writeString(srcDir.resolve("ServiceTest.java"),
                """
                package com.example;
                public class ServiceTest {
                    public void testProcess() {
                        new Service().process();
                    }
                }
                """);

        javaFiles = List.of(
                srcDir.resolve("Service.java"),
                srcDir.resolve("Helper.java"),
                srcDir.resolve("ServiceTest.java"));
    }

    private CommandContext ctx(boolean fullOutput) {
        OutputFormatter formatter = OutputFormatter.create(true, false, null);
        return new CommandContext(javaFiles, tempDir.toString(), null, formatter, null, parser,
                false, null, fullOutput, false);
    }

    // ---- Bug 1: --callers compact showed "null.null" ----

    @Test
    @DisplayName("--callers compact should show real class.method, never null.null")
    void callersCompact_neverShowsNull() {
        var out = new ByteArrayOutputStream();
        var formatter = OutputFormatter.create(true, false, null, new PrintStream(out));
        var context = new CommandContext(javaFiles, tempDir.toString(), null, formatter, null, parser,
                false, null, false, false);

        new CallersCommand("Helper.doWork").execute(context);
        String output = out.toString();
        assertFalse(output.contains("null.null"), "Compact callers should never contain null.null: " + output);
        assertFalse(output.contains("\"null\""), "Compact callers should never contain null values: " + output);
    }

    // ---- Bug 2: --callees compact showed "null.null" ----

    @Test
    @DisplayName("--callees compact should show real class.method, never null.null")
    void calleesCompact_neverShowsNull() {
        var out = new ByteArrayOutputStream();
        var formatter = OutputFormatter.create(true, false, null, new PrintStream(out));
        var context = new CommandContext(javaFiles, tempDir.toString(), null, formatter, null, parser,
                false, null, false, false);

        new CalleesCommand("Service.process").execute(context);
        String output = out.toString();
        assertFalse(output.contains("null.null"), "Compact callees should never contain null.null: " + output);
        assertFalse(output.contains("\"null\""), "Compact callees should never contain null values: " + output);
    }

    // ---- Bug 3: --smells --all was rejected by extractArg ----

    @Test
    @DisplayName("SmellsCommand accepts --all as target")
    void smellsAll_accepted() {
        var out = new ByteArrayOutputStream();
        var formatter = OutputFormatter.create(true, false, null, new PrintStream(out));
        var context = new CommandContext(javaFiles, tempDir.toString(), null, formatter, null, parser,
                false, null, false, false);

        int result = new SmellsCommand("--all").execute(context);
        String output = out.toString();
        // Should produce output, not show usage
        assertFalse(output.contains("Usage:"), "Should not show usage for --all target");
        assertTrue(result >= 0, "Should return non-negative result count");
    }

    // ---- Bug 4: --search rejected OR patterns (TODO|FIXME) ----

    @Test
    @DisplayName("--search supports OR patterns with pipe separator")
    void searchOrPattern_works() {
        var out = new ByteArrayOutputStream();
        var formatter = OutputFormatter.create(true, false, null, new PrintStream(out));
        var context = new CommandContext(javaFiles, tempDir.toString(), null, formatter, null, parser,
                false, null, true, false);

        int result = new SearchCommand("TODO|FIXME").execute(context);
        assertTrue(result >= 2, "Should find both TODO and FIXME comments, got: " + result);
    }

    @Test
    @DisplayName("--search OR finds each alternative independently")
    void searchOrPattern_findsBothAlternatives() {
        var out = new ByteArrayOutputStream();
        var formatter = OutputFormatter.create(true, false, null, new PrintStream(out));
        var context = new CommandContext(javaFiles, tempDir.toString(), null, formatter, null, parser,
                false, null, true, false);

        int todoOnly = new SearchCommand("TODO").execute(context);
        int fixmeOnly = new SearchCommand("FIXME").execute(context);
        int both = new SearchCommand("TODO|FIXME").execute(context);

        assertTrue(both >= todoOnly, "OR search should find at least as many as single TODO");
        assertTrue(both >= fixmeOnly, "OR search should find at least as many as single FIXME");
        assertEquals(todoOnly + fixmeOnly, both, "OR should be union of both searches");
    }

    // ---- Bug 5: --complexity --all was not implemented ----

    @Test
    @DisplayName("ComplexityAllCommand scans all classes and returns top methods")
    void complexityAll_returnsTopMethods() {
        var out = new ByteArrayOutputStream();
        var formatter = OutputFormatter.create(true, false, null, new PrintStream(out));
        var context = new CommandContext(javaFiles, tempDir.toString(), null, formatter, null, parser,
                false, null, false, false);

        int result = new ComplexityAllCommand().execute(context);
        String output = out.toString();
        assertTrue(result > 0, "Should return methods analyzed");
        assertTrue(output.contains("totalMethodsAnalyzed"), "Should include total methods count");
        assertTrue(output.contains("top"), "Should include top methods list");
    }
}
