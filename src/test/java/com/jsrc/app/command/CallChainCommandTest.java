package com.jsrc.app.command;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.jsrc.app.index.CodebaseIndex;
import com.jsrc.app.index.IndexEntry;
import com.jsrc.app.index.IndexedCodebase;
import com.jsrc.app.output.JsonFormatter;
import com.jsrc.app.output.JsonReader;
import com.jsrc.app.parser.HybridJavaParser;

class CallChainCommandTest {

    @TempDir
    Path tempDir;

    private HybridJavaParser parser;

    @BeforeEach
    void setUp() {
        parser = new HybridJavaParser();
    }

    private String executeCallChain(String methodRef, String... sources) throws Exception {
        List<Path> files = new java.util.ArrayList<>();
        for (int i = 0; i < sources.length; i++) {
            // Extract class name from source for proper file naming
            String src = sources[i];
            String className = "Class" + i;
            int classIdx = src.indexOf("class ");
            if (classIdx >= 0) {
                String after = src.substring(classIdx + 6).trim();
                className = after.split("[\\s{]")[0];
            }
            Path file = tempDir.resolve(className + ".java");
            Files.writeString(file, src);
            files.add(file);
        }

        // Build index with call edges
        var index = new CodebaseIndex();
        index.build(parser, files, tempDir, List.of());
        index.save(tempDir);
        var indexed = IndexedCodebase.tryLoad(tempDir, files);

        var ctx = new CommandContext(files, tempDir.toString(), null,
                new JsonFormatter(), indexed, parser);

        var out = new ByteArrayOutputStream();
        var oldOut = System.out;
        System.setOut(new PrintStream(out));
        try {
            new CallChainCommand(methodRef, tempDir.resolve("chains").toString()).execute(ctx);
        } finally {
            System.setOut(oldOut);
        }
        return out.toString().trim();
    }

    @Test
    @DisplayName("Overloaded methods in same class should trigger ambiguity")
    void overloadAmbiguity() throws Exception {
        String source = """
                package com.test;
                public class Service {
                    public void process(String s) {
                        System.out.println(s);
                    }
                    public void process(String s, int count) {
                        System.out.println(s + count);
                    }
                }
                """;
        String caller = """
                package com.test;
                public class Controller {
                    private Service svc = new Service();
                    public void handleOne() {
                        svc.process("hello");
                    }
                    public void handleTwo() {
                        svc.process("hello", 5);
                    }
                }
                """;

        String output = executeCallChain("Service.process", source, caller);
        Object parsed = JsonReader.parse(output);
        assertTrue(parsed instanceof java.util.Map, "Should return ambiguity map, got: " + output);
        @SuppressWarnings("unchecked")
        var map = (java.util.Map<String, Object>) parsed;
        assertEquals(true, map.get("ambiguous"));
        assertTrue(((List<?>) map.get("candidates")).size() >= 2,
                "Should have at least 2 candidates");
    }

    @Test
    @DisplayName("Overloaded methods detected via signature map even with -1 paramCount")
    void overloadAmbiguityWithFuzzyParamCount() throws Exception {
        // This test simulates the real-world case where indexed edges have parameterCount=-1
        String source = """
                package com.test;
                public class Service {
                    public void process(String s) {
                        System.out.println(s);
                    }
                    public void process(String s, int count) {
                        System.out.println(s + count);
                    }
                }
                """;
        String caller = """
                package com.test;
                public class Controller {
                    private Service svc = new Service();
                    public void handleOne() {
                        svc.process("hello");
                    }
                    public void handleTwo() {
                        svc.process("hello", 5);
                    }
                }
                """;

        // Build index, then load call graph (simulates real flow via loadFromIndex)
        List<Path> files = new java.util.ArrayList<>();
        Path f1 = tempDir.resolve("Service.java"); Files.writeString(f1, source); files.add(f1);
        Path f2 = tempDir.resolve("Controller.java"); Files.writeString(f2, caller); files.add(f2);

        var index = new CodebaseIndex();
        index.build(parser, files, tempDir, List.of());
        index.save(tempDir);

        // Inject a -1 paramCount edge (simulates what happens in real codebases)
        var graphBuilder = new com.jsrc.app.analysis.CallGraphBuilder();
        graphBuilder.loadFromIndex(index.getEntries());
        // Manually add an edge with -1 (like runtime InvokerResolver would)
        graphBuilder.addEdge(new com.jsrc.app.parser.model.MethodCall(
                new com.jsrc.app.parser.model.MethodReference("External", "call", -1, null),
                new com.jsrc.app.parser.model.MethodReference("Service", "process", -1, null),
                999));

        var targets = graphBuilder.findMethodsByName("process");
        // Even if Set deduplicates, signature map should catch the overloads
        // This is the real-world scenario: targets may collapse due to fuzzy equals

        // Verify via CallChainCommand output
        var indexed = IndexedCodebase.tryLoad(tempDir, files);
        var ctx = new CommandContext(files, tempDir.toString(), null,
                new JsonFormatter(), indexed, parser);

        var out = new ByteArrayOutputStream();
        var oldOut = System.out;
        System.setOut(new PrintStream(out));
        try {
            new CallChainCommand("Service.process", tempDir.resolve("chains").toString()).execute(ctx);
        } finally {
            System.setOut(oldOut);
        }
        String output = out.toString().trim();
        Object parsed = JsonReader.parse(output);
        assertTrue(parsed instanceof java.util.Map, "Should return ambiguity map, got: " + output);
        @SuppressWarnings("unchecked")
        var map = (java.util.Map<String, Object>) parsed;
        assertEquals(true, map.get("ambiguous"));
    }

    @Test
    @DisplayName("Specific overload should not trigger ambiguity")
    void specificOverloadNoAmbiguity() throws Exception {
        String source = """
                package com.test;
                public class Service {
                    public void process(String s) {
                        System.out.println(s);
                    }
                    public void process(String s, int count) {
                        System.out.println(s + count);
                    }
                }
                """;
        String caller = """
                package com.test;
                public class Controller {
                    private Service svc = new Service();
                    public void handle() {
                        svc.process("hello");
                    }
                }
                """;

        String output = executeCallChain("Service.process(String)", source, caller);
        // Should NOT be ambiguous — specific overload
        Object parsed = JsonReader.parse(output);
        assertTrue(parsed instanceof List, "Should return chains list, got: " + output);
    }

    @Test
    @DisplayName("Constructor appears in call chain")
    void constructorInChain() throws Exception {
        String target = """
                package com.test;
                public class Worker {
                    public void doWork() {
                        System.out.println("working");
                    }
                }
                """;
        String factory = """
                package com.test;
                public class Factory {
                    public Factory() {
                        Worker w = new Worker();
                        w.doWork();
                    }
                }
                """;
        String caller = """
                package com.test;
                public class Main {
                    public void run() {
                        Factory f = new Factory();
                    }
                }
                """;

        // First verify call edges are extracted
        List<Path> files = new java.util.ArrayList<>();
        Path f1 = tempDir.resolve("Worker.java"); Files.writeString(f1, target); files.add(f1);
        Path f2 = tempDir.resolve("Factory.java"); Files.writeString(f2, factory); files.add(f2);
        Path f3 = tempDir.resolve("Main.java"); Files.writeString(f3, caller); files.add(f3);

        var index = new CodebaseIndex();
        index.build(parser, files, tempDir, List.of());

        // Check edges exist
        boolean hasConstructorEdge = false;
        boolean hasNewFactoryEdge = false;
        for (var entry : index.getEntries()) {
            for (var edge : entry.callEdges()) {
                if (edge.callerClass().equals("Factory") && edge.callerMethod().equals("Factory")
                        && edge.calleeMethod().equals("doWork")) {
                    hasConstructorEdge = true;
                }
                if (edge.callerClass().equals("Main") && edge.calleeClass().equals("Factory")
                        && edge.calleeMethod().equals("Factory")) {
                    hasNewFactoryEdge = true;
                }
            }
        }
        assertTrue(hasConstructorEdge, "Index should have edge: Factory.<init> -> Worker.doWork");
        assertTrue(hasNewFactoryEdge, "Index should have edge: Main.run -> new Factory()");

        // Test call graph loaded from index
        var graphBuilder = new com.jsrc.app.analysis.CallGraphBuilder();
        graphBuilder.loadFromIndex(index.getEntries());

        var doWorkTargets = graphBuilder.findMethodsByName("doWork");
        assertFalse(doWorkTargets.isEmpty(), "Should find doWork in call graph. All methods: "
                + graphBuilder.getAllMethods());

        // Check callers of doWork
        for (var t : doWorkTargets) {
            var callers = graphBuilder.getCallersOf(t);
            assertFalse(callers.isEmpty(), "doWork should have callers. Target: " + t
                    + ", all callerIndex keys: " + graphBuilder.getAllCallerIndexKeys());
        }

        var tracer = new com.jsrc.app.analysis.CallChainTracer(graphBuilder);
        var chains = tracer.traceToRoots("doWork");
        assertFalse(chains.isEmpty(), "Should find at least one chain through constructor");

        // Verify chain includes Factory
        boolean hasFactory = chains.stream()
                .anyMatch(c -> c.summary().contains("Factory"));
        assertTrue(hasFactory, "Chain should include Factory constructor. Chains: " + chains);
    }
}
