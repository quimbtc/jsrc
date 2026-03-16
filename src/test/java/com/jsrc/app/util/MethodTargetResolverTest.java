package com.jsrc.app.util;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.jsrc.app.analysis.CallGraphBuilder;
import com.jsrc.app.index.CodebaseIndex;
import com.jsrc.app.parser.HybridJavaParser;
import com.jsrc.app.parser.model.MethodReference;

class MethodTargetResolverTest {

    @TempDir
    Path tempDir;

    private CallGraphBuilder graph;

    @BeforeEach
    void setUp() throws Exception {
        // Build a codebase with overloads and multiple classes
        Path svc = writeFile("Service.java", """
                public class Service {
                    public void process(String s) {}
                    public void process(String s, int n) {}
                    public void handle() {}
                }
                """);
        Path ctrl = writeFile("Controller.java", """
                public class Controller {
                    private Service svc = new Service();
                    public void process(String s) {}
                    public void doA() { svc.process("x"); }
                    public void doB() { svc.process("x", 1); }
                }
                """);

        var parser = new HybridJavaParser();
        var index = new CodebaseIndex();
        index.build(parser, List.of(svc, ctrl), tempDir, List.of());

        graph = new CallGraphBuilder();
        graph.loadFromIndex(index.getEntries());
    }

    @Test
    @DisplayName("methodName only — returns all matches across classes")
    void methodNameOnly() {
        var ref = MethodResolver.parse("process");
        var result = MethodTargetResolver.resolve(ref, graph);

        assertTrue(result.isResolved());
        // Service.process(1), Service.process(2), Controller.process(1)
        assertTrue(result.targets().size() >= 3, "Should find all process methods: " + result.targets());
    }

    @Test
    @DisplayName("Class.method — filters by class")
    void classMethod() {
        var ref = MethodResolver.parse("Service.process");
        var result = MethodTargetResolver.resolve(ref, graph);

        assertTrue(result.isResolved());
        for (MethodReference t : result.targets()) {
            assertEquals("Service", t.className(), "All targets should be in Service");
        }
        assertEquals(2, result.targets().size(), "Should find 2 overloads in Service");
    }

    @Test
    @DisplayName("Class.method(params) — filters by class and param count")
    void classMethodParams() {
        var ref = MethodResolver.parse("Service.process(String,int)");
        var result = MethodTargetResolver.resolve(ref, graph);

        assertTrue(result.isResolved());
        assertEquals(1, result.targets().size(), "Should find exactly 1 overload");
        assertEquals(2, result.targets().iterator().next().parameterCount());
    }

    @Test
    @DisplayName("Qualified name resolved to simple")
    void qualifiedName() {
        var ref = MethodResolver.parse("com.app.Service.process");
        var result = MethodTargetResolver.resolve(ref, graph);

        assertTrue(result.isResolved());
        for (MethodReference t : result.targets()) {
            assertEquals("Service", t.className());
        }
    }

    @Test
    @DisplayName("Ambiguous — multiple classes, no class specified")
    void ambiguous() {
        var ref = MethodResolver.parse("process");
        var result = MethodTargetResolver.resolve(ref, graph);

        // process exists in both Service and Controller — ambiguous
        assertTrue(result.isAmbiguous(), "Should be ambiguous: " + result.targets());
    }

    @Test
    @DisplayName("Not found — unknown method")
    void notFound() {
        var ref = MethodResolver.parse("nonExistent");
        var result = MethodTargetResolver.resolve(ref, graph);

        assertTrue(result.targets().isEmpty());
        assertFalse(result.isAmbiguous());
    }

    @Test
    @DisplayName("Unique method — not ambiguous even without class")
    void uniqueMethod() {
        var ref = MethodResolver.parse("handle");
        var result = MethodTargetResolver.resolve(ref, graph);

        assertTrue(result.isResolved());
        assertFalse(result.isAmbiguous());
        assertEquals(1, result.targets().size());
    }

    private Path writeFile(String name, String content) throws Exception {
        Path file = tempDir.resolve(name);
        Files.writeString(file, content);
        return file;
    }
}
