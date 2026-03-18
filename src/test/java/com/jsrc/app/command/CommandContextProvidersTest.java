package com.jsrc.app.command;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.jsrc.app.analysis.DependencyAnalyzer;
import com.jsrc.app.output.JsonFormatter;
import com.jsrc.app.parser.HybridJavaParser;
import com.jsrc.app.parser.SourceReader;

/**
 * Tests for lazy service providers in CommandContext.
 */
class CommandContextProvidersTest {

    @TempDir
    Path tempDir;

    @Test
    void dependencyAnalyzer_returnsSameInstance() throws Exception {
        var ctx = makeCtx();
        DependencyAnalyzer a1 = ctx.dependencyAnalyzer();
        DependencyAnalyzer a2 = ctx.dependencyAnalyzer();
        assertNotNull(a1);
        assertSame(a1, a2, "dependencyAnalyzer() must be lazy singleton");
    }

    @Test
    void sourceReader_returnsSameInstance() throws Exception {
        var ctx = makeCtx();
        SourceReader r1 = ctx.sourceReader();
        SourceReader r2 = ctx.sourceReader();
        assertNotNull(r1);
        assertSame(r1, r2, "sourceReader() must be lazy singleton");
    }

    private CommandContext makeCtx() throws Exception {
        Path file = tempDir.resolve("Dummy.java");
        Files.writeString(file, "public class Dummy {}");
        return new CommandContext(
                List.of(file), tempDir.toString(), null,
                new JsonFormatter(), null, new HybridJavaParser());
    }
}
