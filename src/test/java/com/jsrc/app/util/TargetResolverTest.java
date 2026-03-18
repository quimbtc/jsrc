package com.jsrc.app.util;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.jsrc.app.index.CodebaseIndex;
import com.jsrc.app.index.IndexedCodebase;
import com.jsrc.app.parser.HybridJavaParser;

class TargetResolverTest {

    @TempDir
    Path tempDir;

    @Test
    void findFileMatches_byClassName() throws Exception {
        Path f = tempDir.resolve("OrderService.java");
        Files.writeString(f, "public class OrderService {}");
        var matches = TargetResolver.findFileMatches(List.of(f), "OrderService");
        assertEquals(1, matches.size());
    }

    @Test
    void findFileMatches_byFileNameWithExtension() throws Exception {
        Path f = tempDir.resolve("Svc.java");
        Files.writeString(f, "public class Svc {}");
        var matches = TargetResolver.findFileMatches(List.of(f), "Svc.java");
        assertEquals(1, matches.size());
    }

    @Test
    void findFileMatches_noMatch() throws Exception {
        Path f = tempDir.resolve("Foo.java");
        Files.writeString(f, "public class Foo {}");
        var matches = TargetResolver.findFileMatches(List.of(f), "Bar");
        assertTrue(matches.isEmpty());
    }

    @Test
    void resolveClassesToFiles_findsMatchingFiles() throws Exception {
        Path f1 = tempDir.resolve("A.java"); Files.writeString(f1, "");
        Path f2 = tempDir.resolve("B.java"); Files.writeString(f2, "");
        Path f3 = tempDir.resolve("C.java"); Files.writeString(f3, "");
        var result = TargetResolver.resolveClassesToFiles(
                List.of(f1, f2, f3), Set.of("A", "C"));
        assertEquals(2, result.size());
    }

    @Test
    void resolveMethodInIndex_findsMethod() throws Exception {
        Path f = tempDir.resolve("Svc.java");
        Files.writeString(f, """
                public class Svc {
                    public void process(String s) {}
                }
                """);
        var parser = new HybridJavaParser();
        var index = new CodebaseIndex();
        index.build(parser, List.of(f), tempDir, List.of());
        index.save(tempDir);
        var indexed = IndexedCodebase.tryLoad(tempDir, List.of(f));

        var ref = MethodResolver.parse("process");
        var result = TargetResolver.resolveMethodInIndex(ref, indexed);
        assertFalse(result.ambiguous());
        assertEquals(1, result.methodMatches().size());
        assertEquals("process", result.methodMatches().getFirst().methodName());
    }

    @Test
    void resolveMethodInIndex_ambiguousAcrossClasses() throws Exception {
        Path f1 = tempDir.resolve("A.java");
        Files.writeString(f1, "public class A { public void run() {} }");
        Path f2 = tempDir.resolve("B.java");
        Files.writeString(f2, "public class B { public void run() {} }");
        var parser = new HybridJavaParser();
        var index = new CodebaseIndex();
        index.build(parser, List.of(f1, f2), tempDir, List.of());
        index.save(tempDir);
        var indexed = IndexedCodebase.tryLoad(tempDir, List.of(f1, f2));

        var ref = MethodResolver.parse("run");
        var result = TargetResolver.resolveMethodInIndex(ref, indexed);
        assertTrue(result.ambiguous());
        assertEquals(2, result.matchingClasses().size());
    }

    @Test
    void resolveMethodInIndex_qualifiedNotAmbiguous() throws Exception {
        Path f1 = tempDir.resolve("A.java");
        Files.writeString(f1, "public class A { public void run() {} }");
        Path f2 = tempDir.resolve("B.java");
        Files.writeString(f2, "public class B { public void run() {} }");
        var parser = new HybridJavaParser();
        var index = new CodebaseIndex();
        index.build(parser, List.of(f1, f2), tempDir, List.of());
        index.save(tempDir);
        var indexed = IndexedCodebase.tryLoad(tempDir, List.of(f1, f2));

        var ref = MethodResolver.parse("A.run");
        var result = TargetResolver.resolveMethodInIndex(ref, indexed);
        assertFalse(result.ambiguous());
        assertEquals(1, result.methodMatches().size());
        assertEquals("A", result.methodMatches().getFirst().className());
    }
}
