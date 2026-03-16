package com.jsrc.app.command;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.jsrc.app.output.JsonFormatter;
import com.jsrc.app.parser.HybridJavaParser;

class SmellsCommandTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("No target should print usage help to stderr")
    void noTargetShowsHelp() throws Exception {
        var ctx = buildContext();
        var errOut = new ByteArrayOutputStream();
        var oldErr = System.err;
        System.setErr(new PrintStream(errOut));
        try {
            int result = new SmellsCommand(null).execute(ctx);
            assertEquals(0, result);
        } finally {
            System.setErr(oldErr);
        }
        String err = errOut.toString();
        assertTrue(err.contains("Usage:"), "Should print usage. Got: " + err);
        assertTrue(err.contains("--all"), "Should mention --all flag");
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
        // At least one smell (too many params in Service)
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
        var out = new ByteArrayOutputStream();
        var oldOut = System.out;
        System.setOut(new PrintStream(out));
        try {
            int result = new SmellsCommand("Service").execute(ctx);
            assertTrue(result >= 1, "Should detect smells in Service");
        } finally {
            System.setOut(oldOut);
        }
        String output = out.toString();
        assertFalse(output.contains("Controller"), "Should not scan Controller");
    }

    @Test
    @DisplayName("Non-matching target prints error")
    void nonMatchingTargetShowsError() throws Exception {
        writeFile("Service.java", """
                public class Service {
                    public void handle() {}
                }
                """);

        var ctx = buildContext();
        var errOut = new ByteArrayOutputStream();
        var oldErr = System.err;
        System.setErr(new PrintStream(errOut));
        try {
            int result = new SmellsCommand("NonExistent").execute(ctx);
            assertEquals(0, result);
        } finally {
            System.setErr(oldErr);
        }
        assertTrue(errOut.toString().contains("No files matching"),
                "Should print not-found message");
    }

    private Path writeFile(String name, String content) throws Exception {
        Path file = tempDir.resolve(name);
        Files.writeString(file, content);
        return file;
    }

    private CommandContext buildContext() throws Exception {
        var files = Files.list(tempDir)
                .filter(p -> p.toString().endsWith(".java"))
                .toList();
        var parser = new HybridJavaParser();
        return new CommandContext(files, tempDir.toString(), null,
                new JsonFormatter(), null, parser);
    }
}
