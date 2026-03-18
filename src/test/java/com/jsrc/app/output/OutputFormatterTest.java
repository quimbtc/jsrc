package com.jsrc.app.output;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.jsrc.app.parser.model.CallChain;
import com.jsrc.app.parser.model.CodeSmell;
import com.jsrc.app.parser.model.CodeSmell.Severity;
import com.jsrc.app.parser.model.MethodCall;
import com.jsrc.app.parser.model.MethodInfo;
import com.jsrc.app.parser.model.MethodInfo.ParameterInfo;
import com.jsrc.app.parser.model.MethodReference;

class OutputFormatterTest {

    // ---- TextFormatter basic contract ----

    @Test
    @DisplayName("TextFormatter should produce human-readable method output")
    void textFormatterMethodOutput() {
        var out = captureOutput(() -> {
            OutputFormatter fmt = new TextFormatter();
            MethodInfo method = MethodInfo.basic("greet", "Hello", 5, 10,
                    "void", List.of("public"), List.of(), "public void greet() {}");
            fmt.printMethods(List.of(method), Path.of("Hello.java"), "greet");
        });
        assertTrue(out.contains("greet"));
        assertTrue(out.contains("Hello"));
        assertFalse(out.startsWith("{"), "Text formatter should not produce JSON");
    }

    @Test
    @DisplayName("TextFormatter should produce human-readable smell output")
    void textFormatterSmellOutput() {
        var out = captureOutput(() -> {
            OutputFormatter fmt = new TextFormatter();
            CodeSmell smell = new CodeSmell("EMPTY_CATCH", Severity.WARNING,
                    "Empty catch block", 10, "process", "Service");
            fmt.printSmells(List.of(smell), Path.of("Service.java"));
        });
        assertTrue(out.contains("EMPTY_CATCH"));
        assertTrue(out.contains("WARNING"));
    }

    // ---- JsonFormatter basic contract ----

    @Test
    @DisplayName("JsonFormatter should produce valid JSON for methods")
    void jsonFormatterMethodOutput() {
        var out = captureOutput(() -> {
            OutputFormatter fmt = new JsonFormatter();
            MethodInfo method = MethodInfo.basic("greet", "Hello", 5, 10,
                    "void", List.of("public"),
                    List.of(new ParameterInfo("String", "name")),
                    "public void greet(String name) {}");
            fmt.printMethods(List.of(method), Path.of("Hello.java"), "greet");
        });
        assertTrue(out.startsWith("["), "JSON formatter should produce array");
        assertTrue(out.contains("\"name\":\"greet\""));
        assertTrue(out.contains("\"className\":\"Hello\""));
        assertTrue(out.contains("\"signature\":"));
        assertFalse(out.contains("\"content\":"), "Should omit content by default");
    }

    @Test
    @DisplayName("JsonFormatter should produce valid JSON for smells")
    void jsonFormatterSmellOutput() {
        var out = captureOutput(() -> {
            OutputFormatter fmt = new JsonFormatter();
            CodeSmell smell = new CodeSmell("MAGIC_NUMBER", Severity.INFO,
                    "Magic number 42", 15, "calc", "Math");
            fmt.printSmells(List.of(smell), Path.of("Math.java"));
        });
        assertTrue(out.startsWith("{"), "Smell JSON should be an object with findings");
        assertTrue(out.contains("\"ruleId\":\"MAGIC_NUMBER\""));
        assertTrue(out.contains("\"severity\":\"INFO\""));
        assertTrue(out.contains("\"summary\""));
    }

    @Test
    @DisplayName("JsonFormatter should produce valid JSON for call chains")
    void jsonFormatterCallChainOutput() {
        var out = captureOutput(() -> {
            OutputFormatter fmt = new JsonFormatter();
            MethodReference app = new MethodReference("App", "main", 0, null);
            MethodReference svc = new MethodReference("Service", "process", 1, null);
            CallChain chain = new CallChain(List.of(new MethodCall(app, svc, 10)));
            fmt.printCallChains(new com.jsrc.app.model.CallChainOutput(List.of(chain), "process"));
        });
        assertTrue(out.startsWith("["), "Call chain JSON should be an array");
        assertTrue(out.contains("\"summary\":"));
        assertTrue(out.contains("\"depth\":1"));
    }

    @Test
    @DisplayName("JsonFormatter should produce empty array for no methods")
    void jsonFormatterEmptyMethods() {
        var out = captureOutput(() -> {
            OutputFormatter fmt = new JsonFormatter();
            fmt.printMethods(List.of(), Path.of("None.java"), "missing");
        });
        assertEquals("[]", out.trim());
    }

    @Test
    @DisplayName("JsonFormatter should produce empty structure for no smells")
    void jsonFormatterEmptySmells() {
        var out = captureOutput(() -> {
            OutputFormatter fmt = new JsonFormatter();
            fmt.printSmells(List.of(), Path.of("Clean.java"));
        });
        assertTrue(out.contains("\"findings\":[]"));
    }

    // ---- OutputFormatter.create factory ----

    @Test
    @DisplayName("Factory should return JsonFormatter when json=true")
    void factoryShouldReturnJson() {
        assertInstanceOf(JsonFormatter.class, OutputFormatter.create(true));
    }

    @Test
    @DisplayName("Factory should return TextFormatter when json=false")
    void factoryShouldReturnText() {
        assertInstanceOf(TextFormatter.class, OutputFormatter.create(false));
    }

    private String captureOutput(Runnable action) {
        PrintStream original = System.out;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        System.setOut(new PrintStream(baos));
        try {
            action.run();
        } finally {
            System.setOut(original);
        }
        return baos.toString().trim();
    }
}
