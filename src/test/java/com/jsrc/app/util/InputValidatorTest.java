package com.jsrc.app.util;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InputValidatorTest {

    // ---- Identifiers ----

    @Test
    @DisplayName("Should accept valid Java identifiers")
    void shouldAcceptValidIdentifiers() {
        assertNull(InputValidator.validateIdentifier("OrderService", "class"));
        assertNull(InputValidator.validateIdentifier("com.app.OrderService", "class"));
        assertNull(InputValidator.validateIdentifier("findById", "method"));
        assertNull(InputValidator.validateIdentifier("$proxy", "class"));
        assertNull(InputValidator.validateIdentifier("_internal", "method"));
    }

    @Test
    @DisplayName("Should reject invalid identifiers")
    void shouldRejectInvalidIdentifiers() {
        assertNotNull(InputValidator.validateIdentifier("", "class"));
        assertNotNull(InputValidator.validateIdentifier(null, "class"));
        assertNotNull(InputValidator.validateIdentifier("123bad", "class"));
        assertNotNull(InputValidator.validateIdentifier("my-class", "class"));
        assertNotNull(InputValidator.validateIdentifier("class name", "class"));
    }

    @Test
    @DisplayName("Should reject identifiers with control characters")
    void shouldRejectControlCharsInIdentifiers() {
        assertNotNull(InputValidator.validateIdentifier("Service\u0000", "class"));
        assertNotNull(InputValidator.validateIdentifier("method\u001F", "method"));
    }

    // ---- Paths ----

    @Test
    @DisplayName("Should accept valid paths")
    void shouldAcceptValidPaths() {
        assertNull(InputValidator.validatePath("src/main/java", "root"));
        assertNull(InputValidator.validatePath("/tmp/spring-boot", "root"));
        assertNull(InputValidator.validatePath(".", "root"));
    }

    @Test
    @DisplayName("Should reject path traversal")
    void shouldRejectPathTraversal() {
        assertNotNull(InputValidator.validatePath("../../etc/passwd", "root"));
        assertNotNull(InputValidator.validatePath("src/../../../secret", "root"));
        assertNotNull(InputValidator.validatePath("..\\windows\\system32", "root"));
    }

    @Test
    @DisplayName("Should reject paths with control chars and null bytes")
    void shouldRejectControlCharsInPaths() {
        assertNotNull(InputValidator.validatePath("src\u0000main", "root"));
        assertNotNull(InputValidator.validatePath("path\u0007dir", "root"));
    }

    @Test
    @DisplayName("Should reject empty paths")
    void shouldRejectEmptyPaths() {
        assertNotNull(InputValidator.validatePath("", "root"));
        assertNotNull(InputValidator.validatePath(null, "root"));
    }

    // ---- Commands ----

    @Test
    @DisplayName("Should accept known commands")
    void shouldAcceptKnownCommands() {
        assertNull(InputValidator.validateCommand("--overview"));
        assertNull(InputValidator.validateCommand("--classes"));
        assertNull(InputValidator.validateCommand("--index"));
        assertNull(InputValidator.validateCommand("--smells"));
    }

    @Test
    @DisplayName("Should accept method name commands")
    void shouldAcceptMethodNames() {
        assertNull(InputValidator.validateCommand("findById"));
        assertNull(InputValidator.validateCommand("process"));
    }

    @Test
    @DisplayName("Should reject unknown flag commands with suggestion")
    void shouldRejectUnknownWithSuggestion() {
        String error = InputValidator.validateCommand("--calsses");
        assertNotNull(error);
        assertTrue(error.contains("Did you mean"), "Should suggest a similar command: " + error);

        error = InputValidator.validateCommand("--summry");
        assertNotNull(error);
        assertTrue(error.contains("Did you mean --summary"));
    }

    @Test
    @DisplayName("Should reject totally unknown commands")
    void shouldRejectTotallyUnknown() {
        String error = InputValidator.validateCommand("--xyzzy");
        assertNotNull(error);
        assertTrue(error.contains("Unknown command"));
    }
}
