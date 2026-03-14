package com.jsrc.app.codebase;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodeBaseLoadTest {

    private CodeBaseLoader loader;

    @BeforeEach
    void setUp() {
        loader = new CodeBaseLoader();
    }

    @Test
    @DisplayName("Should find .java files in nested directory structure")
    void shouldFindJavaFiles(@TempDir Path tempDir) throws IOException {
        Path javaDir = Files.createDirectories(tempDir.resolve("src/main/java"));
        Path testDir = Files.createDirectories(tempDir.resolve("src/test"));

        Path java1 = Files.writeString(javaDir.resolve("Main.java"), "class Main {}");
        Path java2 = Files.writeString(javaDir.resolve("Utils.java"), "class Utils {}");
        Path java3 = Files.writeString(testDir.resolve("Test.java"), "class Test {}");
        Files.writeString(javaDir.resolve("config.txt"), "data");
        Files.writeString(javaDir.resolve("pom.xml"), "<xml/>");

        List<Path> result = loader.loadFilesFrom(tempDir.toString(), "java");

        assertEquals(3, result.size());
        assertTrue(result.contains(java1));
        assertTrue(result.contains(java2));
        assertTrue(result.contains(java3));
    }

    @Test
    @DisplayName("Should return empty list for empty directory")
    void shouldReturnEmptyForEmptyDir(@TempDir Path tempDir) {
        List<Path> result = loader.loadFilesFrom(tempDir.toString(), "java");
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("Should return empty list for non-existent path")
    void shouldReturnEmptyForMissingPath() {
        List<Path> result = loader.loadFilesFrom("/nonexistent/path/42", "java");
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("Should find files in deeply nested directories")
    void shouldFindInDeepNesting(@TempDir Path tempDir) throws IOException {
        Path deep = Files.createDirectories(tempDir.resolve("a/b/c/d"));
        Path file = Files.writeString(deep.resolve("Deep.java"), "class Deep {}");

        List<Path> result = loader.loadFilesFrom(tempDir.toString(), "java");

        assertEquals(1, result.size());
        assertTrue(result.contains(file));
    }

    @Test
    @DisplayName("Should only match exact extension (case-sensitive)")
    void shouldMatchExactExtension(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("Good.java"), "class Good {}");
        Files.writeString(tempDir.resolve("Bad.JAVA"), "class Bad {}");
        Files.writeString(tempDir.resolve("Bad.Java"), "class Bad {}");
        Files.writeString(tempDir.resolve("Bad.javac"), "compiled");
        Files.writeString(tempDir.resolve("Bad.java.bak"), "backup");

        List<Path> result = loader.loadFilesFrom(tempDir.toString(), "java");

        assertEquals(1, result.size());
        assertEquals("Good.java", result.getFirst().getFileName().toString());
    }

    @Test
    @DisplayName("Should not accumulate results between calls (stateless)")
    void shouldBeStateless(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("File.java"), "class File {}");

        List<Path> first = loader.loadFilesFrom(tempDir.toString(), "java");
        List<Path> second = loader.loadFilesFrom(tempDir.toString(), "java");

        assertEquals(first.size(), second.size(), "Repeated calls should return same count");
        assertEquals(1, first.size());
        assertEquals(1, second.size());
    }

    @Test
    @DisplayName("Should support different file extensions")
    void shouldSupportOtherExtensions(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("style.css"), "body {}");
        Files.writeString(tempDir.resolve("app.js"), "console.log()");
        Files.writeString(tempDir.resolve("Main.java"), "class Main {}");

        assertEquals(1, loader.loadFilesFrom(tempDir.toString(), "css").size());
        assertEquals(1, loader.loadFilesFrom(tempDir.toString(), "js").size());
        assertEquals(1, loader.loadFilesFrom(tempDir.toString(), "java").size());
    }
}
