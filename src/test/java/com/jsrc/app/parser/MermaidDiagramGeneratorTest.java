package com.jsrc.app.parser;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.jsrc.app.parser.model.CallChain;
import com.jsrc.app.parser.model.MethodCall;
import com.jsrc.app.parser.model.MethodReference;

class MermaidDiagramGeneratorTest {

    private MermaidDiagramGenerator generator;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        generator = new MermaidDiagramGenerator();
    }

    @Test
    @DisplayName("Should generate valid Mermaid syntax for simple chain")
    void shouldGenerateSimpleChain() {
        MethodReference app = ref("App", "main");
        MethodReference svc = ref("Service", "process");

        CallChain chain = new CallChain(List.of(
                new MethodCall(app, svc, 10)
        ));

        String diagram = generator.generate(chain);
        assertTrue(diagram.contains("sequenceDiagram"));
        assertTrue(diagram.contains("participant App"));
        assertTrue(diagram.contains("participant Service"));
        assertTrue(diagram.contains("App->>Service: process()"));
    }

    @Test
    @DisplayName("Should generate intra-class calls correctly")
    void shouldGenerateIntraClassCall() {
        MethodReference init = ref("Widget", "init");
        MethodReference configure = ref("Widget", "configure");

        CallChain chain = new CallChain(List.of(
                new MethodCall(init, configure, 5)
        ));

        String diagram = generator.generate(chain);
        assertTrue(diagram.contains("Widget->>Widget: configure()"));
        long participantCount = diagram.lines()
                .filter(l -> l.trim().startsWith("participant Widget"))
                .count();
        assertEquals(1, participantCount, "Widget should appear as participant only once");
    }

    @Test
    @DisplayName("Should generate multi-step chain with unique participants in order")
    void shouldGenerateMultiStepChain() {
        MethodReference controller = ref("Controller", "handle");
        MethodReference service = ref("Service", "process");
        MethodReference repo = ref("Repository", "save");

        CallChain chain = new CallChain(List.of(
                new MethodCall(controller, service, 10),
                new MethodCall(service, repo, 25)
        ));

        String diagram = generator.generate(chain);

        int controllerIdx = diagram.indexOf("participant Controller");
        int serviceIdx = diagram.indexOf("participant Service");
        int repoIdx = diagram.indexOf("participant Repository");

        assertTrue(controllerIdx < serviceIdx, "Controller should appear before Service");
        assertTrue(serviceIdx < repoIdx, "Service should appear before Repository");

        assertTrue(diagram.contains("Controller->>Service: process()"));
        assertTrue(diagram.contains("Service->>Repository: save()"));
    }

    @Test
    @DisplayName("Should include summary comment in header")
    void shouldIncludeSummaryComment() {
        MethodReference a = ref("A", "start");
        MethodReference b = ref("B", "finish");

        CallChain chain = new CallChain(List.of(
                new MethodCall(a, b, 1)
        ));

        String diagram = generator.generate(chain);
        assertTrue(diagram.startsWith("%%"), "Diagram should start with a comment");
        assertTrue(diagram.contains("A.start()"));
        assertTrue(diagram.contains("B.finish()"));
    }

    @Test
    @DisplayName("Should write .mmd files to output directory")
    void shouldWriteFiles() throws IOException {
        MethodReference a = ref("A", "run");
        MethodReference b = ref("B", "exec");

        List<CallChain> chains = List.of(
                new CallChain(List.of(new MethodCall(a, b, 1))),
                new CallChain(List.of(new MethodCall(b, a, 5)))
        );

        Path outDir = tempDir.resolve("diagrams");
        List<Path> files = generator.writeAll(chains, outDir, "exec");

        assertEquals(2, files.size());
        assertTrue(Files.exists(outDir.resolve("exec_chain_1.mmd")));
        assertTrue(Files.exists(outDir.resolve("exec_chain_2.mmd")));

        String content = Files.readString(files.getFirst());
        assertTrue(content.contains("sequenceDiagram"));
    }

    @Test
    @DisplayName("Should create output directory if it does not exist")
    void shouldCreateOutputDirectory() throws IOException {
        MethodReference a = ref("X", "go");
        MethodReference b = ref("Y", "stop");

        List<CallChain> chains = List.of(
                new CallChain(List.of(new MethodCall(a, b, 1)))
        );

        Path outDir = tempDir.resolve("nested").resolve("output");
        assertFalse(Files.exists(outDir));

        generator.writeAll(chains, outDir, "stop");
        assertTrue(Files.isDirectory(outDir));
    }

    private MethodReference ref(String className, String methodName) {
        return new MethodReference(className, methodName, 0, null);
    }
}
