package com.jsrc.app.command;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.jsrc.app.index.CodebaseIndex;
import com.jsrc.app.index.IndexedCodebase;
import com.jsrc.app.output.JsonFormatter;
import com.jsrc.app.output.JsonReader;
import com.jsrc.app.parser.HybridJavaParser;

class SmellsCommandTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("No target should print usage help to stderr")
    void noTargetShowsHelp() throws Exception {
        writeFile("Service.java", "public class Service { public void handle() {} }");
        var ctx = buildContext();
        var errOut = captureStderr(() -> {
            int result = new SmellsCommand(null).execute(ctx);
            assertEquals(0, result);
        });
        assertTrue(errOut.contains("Usage:"), "Should print usage. Got: " + errOut);
        assertTrue(errOut.contains("--all"), "Should mention --all flag");
    }

    @Test
    @DisplayName("--all scans entire codebase")
    void allScansEverything() throws Exception {
        writeFile("Service.java", """
                public class Service {
                    public void doStuff(int a, int b, int c, int d, int e,
                                        int f, int g, int h) {}
                }
                """);
        writeFile("Controller.java", """
                public class Controller {
                    public void handle() {}
                }
                """);

        var ctx = buildContext();
        int result = new SmellsCommand("--all").execute(ctx);
        assertTrue(result >= 1, "Should detect smells in full codebase scan");
    }

    @Test
    @DisplayName("Specific class scans only matching file")
    void specificClassScansOnly() throws Exception {
        writeFile("Service.java", """
                public class Service {
                    public void doStuff(int a, int b, int c, int d, int e,
                                        int f, int g, int h) {}
                }
                """);
        writeFile("Controller.java", """
                public class Controller {
                    public void handle() {}
                }
                """);

        var ctx = buildContext();
        String output = captureStdout(() -> {
            int result = new SmellsCommand("Service").execute(ctx);
            assertTrue(result >= 1, "Should detect smells in Service");
        });
        assertFalse(output.contains("Controller"), "Should not scan Controller");
    }

    @Test
    @DisplayName("Method name resolves to containing class via index")
    void methodNameResolvesToClass() throws Exception {
        writeFile("Service.java", """
                public class Service {
                    public void doStuff(int a, int b, int c, int d, int e,
                                        int f, int g, int h) {}
                }
                """);
        writeFile("Controller.java", """
                public class Controller {
                    public void handle() {}
                }
                """);

        var ctx = buildIndexedContext();
        String output = captureStdout(() -> {
            int result = new SmellsCommand("doStuff").execute(ctx);
            assertTrue(result >= 1, "Should detect smells via method name lookup");
        });
        assertTrue(output.contains("Service"), "Should scan Service (contains doStuff)");
        assertFalse(output.contains("Controller"), "Should not scan Controller");
    }

    @Test
    @DisplayName("Ambiguous method name across classes triggers disambiguation")
    @SuppressWarnings("unchecked")
    void ambiguousMethodShowsCandidates() throws Exception {
        writeFile("ServiceA.java", """
                public class ServiceA {
                    public void process() {}
                }
                """);
        writeFile("ServiceB.java", """
                public class ServiceB {
                    public void process(String s) {}
                }
                """);

        var ctx = buildIndexedContext();
        String output = captureStdout(() -> {
            new SmellsCommand("process").execute(ctx);
        });
        Object parsed = JsonReader.parse(output);
        assertTrue(parsed instanceof Map, "Should return ambiguity map, got: " + output);
        var map = (Map<String, Object>) parsed;
        assertEquals(true, map.get("ambiguous"));
        var candidates = (List<String>) map.get("candidates");
        assertTrue(candidates.size() >= 2, "Should have at least 2 candidates");
    }

    @Test
    @DisplayName("Qualified Class.method disambiguates")
    void qualifiedMethodDisambiguates() throws Exception {
        writeFile("ServiceA.java", """
                public class ServiceA {
                    public void process(int a, int b, int c, int d, int e,
                                        int f, int g, int h) {}
                }
                """);
        writeFile("ServiceB.java", """
                public class ServiceB {
                    public void process() {}
                }
                """);

        var ctx = buildIndexedContext();
        String output = captureStdout(() -> {
            int result = new SmellsCommand("ServiceA.process").execute(ctx);
            assertTrue(result >= 1, "Should detect smells in ServiceA");
        });
        assertTrue(output.contains("ServiceA"), "Should scan ServiceA");
        assertFalse(output.contains("ServiceB"), "Should not scan ServiceB");
    }

    @Test
    @DisplayName("Method target filters smells to that method only")
    void methodTargetFiltersSmells() throws Exception {
        writeFile("Service.java", """
                public class Service {
                    public void clean() {}
                    public void smelly(int a, int b, int c, int d, int e,
                                       int f, int g, int h) {}
                }
                """);

        var ctx = buildIndexedContext();
        // Ask for smells of "smelly" — should include smells
        String output = captureStdout(() -> {
            int result = new SmellsCommand("smelly").execute(ctx);
            assertTrue(result >= 1, "Should detect smells in smelly method");
        });
        assertTrue(output.contains("smelly"), "Should include smelly method smells");

        // Ask for smells of "clean" — should have no smells
        String cleanOutput = captureStdout(() -> {
            int result = new SmellsCommand("clean").execute(ctx);
            assertEquals(0, result, "clean method should have no smells");
        });
        assertFalse(cleanOutput.contains("smelly"),
                "Should NOT include smells from other methods");
    }

    @Test
    @DisplayName("Non-matching target prints error")
    void nonMatchingTargetShowsError() throws Exception {
        writeFile("Service.java", "public class Service { public void handle() {} }");
        var ctx = buildIndexedContext();
        var errOut = captureStderr(() -> {
            int result = new SmellsCommand("NonExistent").execute(ctx);
            assertEquals(0, result);
        });
        assertTrue(errOut.contains("No files matching"),
                "Should print not-found message. Got: " + errOut);
    }

    // --- helpers ---

    private Path writeFile(String name, String content) throws Exception {
        Path file = tempDir.resolve(name);
        Files.writeString(file, content);
        return file;
    }

    private List<Path> javaFiles() throws Exception {
        return Files.list(tempDir)
                .filter(p -> p.toString().endsWith(".java"))
                .toList();
    }

    private CommandContext buildContext() throws Exception {
        var files = javaFiles();
        var parser = new HybridJavaParser();
        return new CommandContext(files, tempDir.toString(), null,
                new JsonFormatter(), null, parser);
    }

    private CommandContext buildIndexedContext() throws Exception {
        var files = javaFiles();
        var parser = new HybridJavaParser();
        var index = new CodebaseIndex();
        index.build(parser, files, tempDir, List.of());
        index.save(tempDir);
        var indexed = IndexedCodebase.tryLoad(tempDir, files);
        return new CommandContext(files, tempDir.toString(), null,
                new JsonFormatter(), indexed, parser);
    }

    private String captureStdout(Runnable action) {
        var out = new ByteArrayOutputStream();
        var old = System.out;
        System.setOut(new PrintStream(out));
        try {
            action.run();
        } finally {
            System.setOut(old);
        }
        return out.toString().trim();
    }

    private String captureStderr(Runnable action) {
        var out = new ByteArrayOutputStream();
        var old = System.err;
        System.setErr(new PrintStream(out));
        try {
            action.run();
        } finally {
            System.setErr(old);
        }
        return out.toString().trim();
    }
}
