package com.jsrc.app.analysis;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.jsrc.app.parser.model.CallChain;

class CallChainTracerTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Should trace linear chain: main -> process -> validate")
    void shouldTraceLinearChain() throws IOException {
        Path file = writeFile("Linear.java", """
                public class Linear {
                    public void main() {
                        process();
                    }
                    public void process() {
                        validate();
                    }
                    public void validate() {}
                }
                """);

        CallGraphBuilder graph = buildGraph(file);
        CallChainTracer tracer = new CallChainTracer(graph);
        List<CallChain> chains = tracer.traceToRoots("validate");

        assertEquals(1, chains.size(), "Should find exactly one chain");
        CallChain chain = chains.getFirst();
        assertEquals(2, chain.depth(), "Chain should have 2 steps");
        assertEquals("main", chain.root().methodName());
        assertEquals("validate", chain.target().methodName());
    }

    @Test
    @DisplayName("Should find multiple chains when method is called from different paths")
    void shouldFindMultipleChains() throws IOException {
        Path file = writeFile("Branch.java", """
                public class Branch {
                    public void entryA() {
                        target();
                    }
                    public void entryB() {
                        target();
                    }
                    public void target() {}
                }
                """);

        CallGraphBuilder graph = buildGraph(file);
        CallChainTracer tracer = new CallChainTracer(graph);
        List<CallChain> chains = tracer.traceToRoots("target");

        assertEquals(2, chains.size(), "Should find two chains (one from entryA, one from entryB)");
        assertTrue(chains.stream().anyMatch(c -> c.root().methodName().equals("entryA")));
        assertTrue(chains.stream().anyMatch(c -> c.root().methodName().equals("entryB")));
    }

    @Test
    @DisplayName("Should handle cycles without infinite loop")
    void shouldHandleCycles() throws IOException {
        Path file = writeFile("Cyclic.java", """
                public class Cyclic {
                    public void a() { b(); }
                    public void b() { a(); target(); }
                    public void target() {}
                }
                """);

        CallGraphBuilder graph = buildGraph(file);
        CallChainTracer tracer = new CallChainTracer(graph);
        List<CallChain> chains = tracer.traceToRoots("target");

        assertFalse(chains.isEmpty(), "Should find at least one chain despite cycle");
        for (CallChain chain : chains) {
            assertTrue(chain.depth() <= 20, "Chain depth should be within limit");
        }
    }

    @Test
    @DisplayName("Should respect max depth limit")
    void shouldRespectMaxDepth() throws IOException {
        Path file = writeFile("Deep.java", """
                public class Deep {
                    public void a() { b(); }
                    public void b() { c(); }
                    public void c() { d(); }
                    public void d() { e(); }
                    public void e() {}
                }
                """);

        CallGraphBuilder graph = buildGraph(file);
        CallChainTracer tracer = new CallChainTracer(graph, 2, java.util.Set.of());
        List<CallChain> chains = tracer.traceToRoots("e");

        assertFalse(chains.isEmpty());
        for (CallChain chain : chains) {
            assertTrue(chain.depth() <= 2, "Chain depth should not exceed maxDepth=2");
        }
    }

    @Test
    @DisplayName("Should return empty list for method with no callers")
    void shouldReturnEmptyForNoCaller() throws IOException {
        Path file = writeFile("Lonely.java", """
                public class Lonely {
                    public void orphan() {}
                }
                """);

        CallGraphBuilder graph = buildGraph(file);
        CallChainTracer tracer = new CallChainTracer(graph);
        List<CallChain> chains = tracer.traceToRoots("orphan");

        assertTrue(chains.isEmpty(), "Orphan method should have no call chains");
    }

    @Test
    @DisplayName("Should return empty for unknown method name")
    void shouldReturnEmptyForUnknownMethod() throws IOException {
        Path file = writeFile("Known.java", """
                public class Known {
                    public void exists() {}
                }
                """);

        CallGraphBuilder graph = buildGraph(file);
        CallChainTracer tracer = new CallChainTracer(graph);
        List<CallChain> chains = tracer.traceToRoots("doesNotExist");

        assertTrue(chains.isEmpty());
    }

    @Test
    @DisplayName("Should trace cross-class call chains")
    void shouldTraceCrossClassChains() throws IOException {
        Path serviceFile = writeFile("Service.java", """
                public class Service {
                    public void doWork() {}
                }
                """);
        Path controllerFile = writeFile("Controller.java", """
                public class Controller {
                    private Service service;
                    public void handle() {
                        service.doWork();
                    }
                }
                """);

        CallGraphBuilder graph = new CallGraphBuilder();
        graph.build(List.of(serviceFile, controllerFile));
        CallChainTracer tracer = new CallChainTracer(graph);
        List<CallChain> chains = tracer.traceToRoots("doWork");

        assertEquals(1, chains.size());
        CallChain chain = chains.getFirst();
        assertEquals("Controller", chain.root().className());
        assertEquals("Service", chain.target().className());
    }

    @Test
    @DisplayName("Should reject null graph")
    void shouldRejectNullGraph() {
        assertThrows(IllegalArgumentException.class, () -> new CallChainTracer(null));
    }

    @Test
    @DisplayName("Should reject invalid max depth")
    void shouldRejectInvalidMaxDepth() throws IOException {
        Path file = writeFile("Dummy.java", """
                public class Dummy { public void x() {} }
                """);
        CallGraphBuilder graph = buildGraph(file);
        assertThrows(IllegalArgumentException.class, () -> new CallChainTracer(graph, 0, java.util.Set.of()));
    }

    // ---- stopMethods ----

    @Test
    @DisplayName("stopMethods cuts chain at exact method name")
    void stopMethodExact() throws IOException {
        Path file = writeFile("StopTest.java", """
                public class StopTest {
                    public void start() { middle(); }
                    public void middle() { end(); }
                    public void end() {}
                }
                """);

        CallGraphBuilder graph = buildGraph(file);
        CallChainTracer tracer = new CallChainTracer(graph, 20, java.util.Set.of("start"));
        List<CallChain> chains = tracer.traceToRoots("end");

        assertFalse(chains.isEmpty(), "Should find chains");
        for (CallChain chain : chains) {
            // Chain root should be 'start' (the stop method becomes the entry point)
            // It should NOT trace further up beyond the stop method
            assertEquals("start", chain.root().methodName(),
                    "Stop method should be the chain root. Root: " + chain.root());
            // Depth should be 2: start→middle→end, not deeper
            assertTrue(chain.depth() <= 2,
                    "Chain should not go beyond stop method. Depth: " + chain.depth());
        }
    }

    @Test
    @DisplayName("stopMethods with glob prefix pattern")
    void stopMethodGlobPrefix() throws IOException {
        Path file = writeFile("ViewTest.java", """
                public class ViewTest {
                    public void getBtnSave() { doSave(); }
                    public void doSave() { save(); }
                    public void save() {}
                }
                """);

        CallGraphBuilder graph = buildGraph(file);
        CallChainTracer tracer = new CallChainTracer(graph, 20, java.util.Set.of("getBtn*"));
        List<CallChain> chains = tracer.traceToRoots("save");

        assertFalse(chains.isEmpty());
        for (CallChain chain : chains) {
            // getBtnSave is the stop method — it becomes the root (entry point)
            assertEquals("getBtnSave", chain.root().methodName(),
                    "Stop method should be chain root. Root: " + chain.root());
        }
    }

    @Test
    @DisplayName("stopMethods with glob contains pattern")
    void stopMethodGlobContains() throws IOException {
        Path file = writeFile("PanelTest.java", """
                public class PanelTest {
                    public void getSeleccionarBtn() { doAction(); }
                    public void doAction() { work(); }
                    public void work() {}
                }
                """);

        CallGraphBuilder graph = buildGraph(file);
        CallChainTracer tracer = new CallChainTracer(graph, 20, java.util.Set.of("get*Btn"));
        List<CallChain> chains = tracer.traceToRoots("work");

        assertFalse(chains.isEmpty());
        for (CallChain chain : chains) {
            assertEquals("getSeleccionarBtn", chain.root().methodName(),
                    "Stop method should be chain root. Root: " + chain.root());
        }
    }

    @Test
    @DisplayName("Should trace separate chains for overloaded method calls")
    void shouldTraceSeparateChainsForOverloads() throws IOException {
        // Reproduces the bug: pxAceptarFacturacion calls two overloads of generaDocumentos
        // Both chains should appear — the dedup by summary() must not collapse them
        Path docs = writeFile("GeneracionDocumentos.java", """
                public class GeneracionDocumentos {
                    public Object generaDocumentos(Integer id) {
                        return _generaDocumentos(id);
                    }
                    public Object generaDocumentos(Integer id, Object dir) {
                        return _generaDocumentos(id, dir);
                    }
                    private Object _generaDocumentos(Integer id) { return null; }
                    private Object _generaDocumentos(Integer id, Object dir) { return null; }
                }
                """);
        Path caller = writeFile("Caller.java", """
                public class Caller {
                    private GeneracionDocumentos docs = new GeneracionDocumentos();
                    public void pxAceptar() {
                        Object a = docs.generaDocumentos(1, null);
                        Object b = docs.generaDocumentos(1);
                    }
                }
                """);

        CallGraphBuilder graph = buildGraph(docs, caller);
        CallChainTracer tracer = new CallChainTracer(graph);

        // Trace to _generaDocumentos — should find 2 chains (one per overload)
        List<CallChain> chains = tracer.traceToRoots("_generaDocumentos");
        assertEquals(2, chains.size(),
                "Should find 2 separate chains for 2 overloads of _generaDocumentos. Got: "
                + chains.stream().map(CallChain::summary).toList());

        // Verify each chain has a distinct summary
        String summary1 = chains.get(0).summary();
        String summary2 = chains.get(1).summary();
        assertNotEquals(summary1, summary2,
                "Overloaded chains should have different summaries");
    }

    private CallGraphBuilder buildGraph(Path... files) {
        CallGraphBuilder graph = new CallGraphBuilder();
        graph.build(List.of(files));
        return graph;
    }

    private Path writeFile(String name, String content) throws IOException {
        Path file = tempDir.resolve(name);
        Files.writeString(file, content);
        return file;
    }
}
