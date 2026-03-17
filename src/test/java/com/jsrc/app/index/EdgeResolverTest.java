package com.jsrc.app.index;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.javaparser.JavaParser;

class EdgeResolverTest {

    @TempDir
    Path tempDir;

    // ---- extractCallEdges ----

    @Test
    @DisplayName("extractCallEdges resolves field type from NameExpr scope")
    void extractCallEdgesResolvesFieldType() throws IOException {
        Path file = writeFile("WithField.java", """
                public class WithField {
                    private Service svc = new Service();
                    public void run() { svc.process(); }
                }
                """);

        var resolver = new EdgeResolver();
        List<CallEdge> edges = resolver.extractCallEdges(file, new JavaParser());

        assertTrue(edges.stream().anyMatch(e ->
                e.calleeMethod().equals("process") && e.calleeClass().equals("Service")),
                "Should resolve field type 'svc' to 'Service'");
    }

    @Test
    @DisplayName("extractCallEdges produces FieldAccessExpr markers")
    void extractCallEdgesProducesFieldAccessMarker() throws IOException {
        Path file = writeFile("Caller.java", """
                public class Caller {
                    public void run(Order order) {
                        order.customer.getAddress();
                    }
                }
                """);

        var resolver = new EdgeResolver();
        List<CallEdge> edges = resolver.extractCallEdges(file, new JavaParser());

        // Before marker resolution, should have ?field: marker
        assertTrue(edges.stream().anyMatch(e ->
                e.calleeMethod().equals("getAddress")
                        && e.calleeClass().startsWith("?field:")),
                "Should produce ?field: marker for order.customer.getAddress()");
    }

    @Test
    @DisplayName("extractCallEdges includes constructor edges")
    void extractCallEdgesIncludesConstructors() throws IOException {
        Path file = writeFile("Factory.java", """
                public class Factory {
                    public Factory() { init(); }
                    private void init() {}
                }
                """);

        var resolver = new EdgeResolver();
        List<CallEdge> edges = resolver.extractCallEdges(file, new JavaParser());

        assertTrue(edges.stream().anyMatch(e ->
                e.callerMethod().equals("Factory") && e.calleeMethod().equals("init")),
                "Should extract edge from constructor to init()");
    }

    // ---- resolveMarkers ----

    @Test
    @DisplayName("resolveMarkers resolves ?field: markers in entries")
    void resolveMarkersResolvesFieldMarkers() {
        var entries = new java.util.ArrayList<>(List.of(
                new IndexEntry("Order.java", "h1", 0,
                        List.of(new IndexedClass("Order", "com.app", 1, 10,
                                false, false, List.of(), List.of(), List.of(), List.of(), List.of(),
                                List.of(new IndexedField("customer", "Customer")))),
                        List.of()),
                new IndexEntry("Processor.java", "h2", 0,
                        List.of(new IndexedClass("Processor", "com.app", 1, 10,
                                false, false, List.of(), List.of(), List.of(), List.of(), List.of(), List.of())),
                        List.of(new CallEdge("Processor", "process", 1,
                                "?field:Order.customer", "getAddress", 3, 0)))
        ));

        var resolver = new EdgeResolver();
        resolver.resolveMarkers(entries);

        boolean resolved = entries.stream()
                .flatMap(e -> e.callEdges().stream())
                .anyMatch(e -> e.calleeClass().equals("Customer") && e.calleeMethod().equals("getAddress"));
        assertTrue(resolved, "?field:Order.customer should resolve to Customer");
    }

    @Test
    @DisplayName("resolveMarkers resolves nested ?field:?ret: chains")
    void resolveMarkersResolvesNestedChains() {
        var entries = new java.util.ArrayList<>(List.of(
                new IndexEntry("Factory.java", "h1", 0,
                        List.of(new IndexedClass("Factory", "", 1, 5,
                                false, false, List.of(), List.of(),
                                List.of(), List.of(), List.of(),
                                List.of(new IndexedField("service", "Service")))),
                        List.of()),
                new IndexEntry("Service.java", "h2", 0,
                        List.of(new IndexedClass("Service", "", 1, 5,
                                false, false, List.of(), List.of(),
                                List.of(), List.of(), List.of(),
                                List.of(new IndexedField("buffer", "StringBuilder")))),
                        List.of()),
                new IndexEntry("Caller.java", "h3", 0,
                        List.of(new IndexedClass("Caller", "", 1, 5,
                                false, false, List.of(), List.of(),
                                List.of(new IndexedMethod("getFactory", "Factory getFactory()", 2, 2, "Factory", List.of())),
                                List.of(), List.of(), List.of())),
                        List.of(new CallEdge("Caller", "run", 0,
                                "?field:?field:?ret:Caller.getFactory.service.buffer", "toString", 4, 0)))
        ));

        var resolver = new EdgeResolver();
        resolver.resolveMarkers(entries);

        boolean resolved = entries.stream()
                .flatMap(e -> e.callEdges().stream())
                .anyMatch(e -> e.calleeClass().equals("StringBuilder") && e.calleeMethod().equals("toString"));
        assertTrue(resolved, "Nested ?field:?ret: chain should resolve to StringBuilder");
    }

    private Path writeFile(String name, String content) throws IOException {
        Path file = tempDir.resolve(name);
        Files.writeString(file, content);
        return file;
    }
}
