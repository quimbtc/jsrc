package com.jsrc.app.integration;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;

import com.jsrc.app.index.CodebaseIndex;
import com.jsrc.app.index.IndexedCodebase;
import com.jsrc.app.parser.HybridJavaParser;
import com.jsrc.app.parser.model.ClassInfo;

/**
 * Integration test: verifies jsrc doesn't miss any types or methods.
 * Compares jsrc output against JavaParser direct parse (ground truth).
 * <p>
 * Runs against jsrc's OWN source code — always available, no external deps.
 * <p>
 * Execute: {@code mvn test -Dgroups=integration}
 * <p>
 * NOT part of the default test suite (tagged "integration").
 * Runs in CI or manually. Takes ~10s.
 */
@Tag("integration")
class CompletenessAuditTest {

    private static final Path SOURCE_ROOT = Paths.get("src/main/java").toAbsolutePath();

    private static List<Path> javaFiles;
    private static List<ClassInfo> jsrcClasses;
    private static int groundTruthTypes;
    private static int groundTruthMethods;
    private static int groundTruthParseFails;

    @TempDir
    static Path tempDir;

    @BeforeAll
    static void setUp() throws Exception {
        assertTrue(Files.exists(SOURCE_ROOT),
                "Source root must exist: " + SOURCE_ROOT);

        javaFiles = Files.walk(SOURCE_ROOT)
                .filter(p -> p.toString().endsWith(".java"))
                .collect(Collectors.toList());
        assertTrue(javaFiles.size() > 50,
                "Should have 50+ Java files. Found: " + javaFiles.size());

        // === jsrc side ===
        var parser = new HybridJavaParser();
        var index = new CodebaseIndex();
        index.build(parser, javaFiles, SOURCE_ROOT, List.of());
        index.save(tempDir);
        var indexed = IndexedCodebase.tryLoad(tempDir, javaFiles);
        assertNotNull(indexed, "Index should load");
        jsrcClasses = indexed.getAllClasses();

        // === Ground truth: JavaParser direct ===
        var config = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
        var jp = new JavaParser(config);

        groundTruthTypes = 0;
        groundTruthMethods = 0;
        groundTruthParseFails = 0;

        for (Path file : javaFiles) {
            try {
                String source = Files.readString(file);
                var result = jp.parse(source);
                if (!result.getResult().isPresent()) {
                    groundTruthParseFails++;
                    continue;
                }
                var cu = result.getResult().get();

                for (var cid : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                    groundTruthTypes++;
                    groundTruthMethods += cid.getMethods().size() + cid.getConstructors().size();
                }
                for (var rd : cu.findAll(RecordDeclaration.class)) {
                    groundTruthTypes++;
                    groundTruthMethods += rd.getMethods().size();
                }
                for (var ed : cu.findAll(EnumDeclaration.class)) {
                    groundTruthTypes++;
                    groundTruthMethods += ed.getMethods().size() + ed.getConstructors().size();
                }
            } catch (IOException e) {
                groundTruthParseFails++;
            }
        }
    }

    @Test
    void typeCount_matchesGroundTruth() {
        assertEquals(groundTruthTypes, jsrcClasses.size(),
                "Type count: ground truth=" + groundTruthTypes + " jsrc=" + jsrcClasses.size());
    }

    @Test
    void methodCount_matchesGroundTruth() {
        int jsrcMethods = jsrcClasses.stream()
                .mapToInt(ci -> ci.methods().size())
                .sum();
        assertEquals(groundTruthMethods, jsrcMethods,
                "Method count: ground truth=" + groundTruthMethods + " jsrc=" + jsrcMethods);
    }

    @Test
    void zeroParseFails() {
        assertEquals(0, groundTruthParseFails,
                "JavaParser should parse all jsrc source files");
    }

    @Test
    void everyClassHasNonNullPackage() {
        for (ClassInfo ci : jsrcClasses) {
            assertNotNull(ci.packageName(),
                    "Class " + ci.name() + " has null package");
        }
    }

    @Test
    void everyMethodHasNameAndLineRange() {
        for (ClassInfo ci : jsrcClasses) {
            for (var m : ci.methods()) {
                assertNotNull(m.name(),
                        "Null method name in " + ci.name());
                assertFalse(m.name().isEmpty(),
                        "Empty method name in " + ci.name());
                assertTrue(m.startLine() > 0,
                        ci.name() + "." + m.name() + " invalid startLine=" + m.startLine());
                assertTrue(m.endLine() >= m.startLine(),
                        ci.name() + "." + m.name() + " endLine < startLine");
            }
        }
    }

    @Test
    void indexRoundtrip_preservesCounts() throws Exception {
        // Load index from disk and verify counts match
        var reloaded = IndexedCodebase.tryLoad(tempDir, javaFiles);
        assertNotNull(reloaded);
        assertEquals(jsrcClasses.size(), reloaded.getAllClasses().size(),
                "Index roundtrip should preserve type count");

        int reloadedMethods = reloaded.getAllClasses().stream()
                .mapToInt(ci -> ci.methods().size())
                .sum();
        int originalMethods = jsrcClasses.stream()
                .mapToInt(ci -> ci.methods().size())
                .sum();
        assertEquals(originalMethods, reloadedMethods,
                "Index roundtrip should preserve method count");
    }
}
