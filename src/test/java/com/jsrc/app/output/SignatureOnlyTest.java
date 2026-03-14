package com.jsrc.app.output;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.jsrc.app.parser.model.AnnotationInfo;
import com.jsrc.app.parser.model.MethodInfo;
import com.jsrc.app.parser.model.MethodInfo.ParameterInfo;

class SignatureOnlyTest {

    @Test
    @DisplayName("JsonFormatter with signatureOnly should emit only signature fields")
    void jsonSignatureOnly() {
        String out = captureOutput(() -> {
            JsonFormatter fmt = new JsonFormatter(true);
            MethodInfo method = new MethodInfo("process", "Service", 10, 50,
                    "String", List.of("public"),
                    List.of(new ParameterInfo("int", "id")),
                    "public String process(int id) { return null; }",
                    List.of(AnnotationInfo.marker("Override")),
                    List.of("IOException"), List.of(), "/** Does stuff */");
            fmt.printMethods(List.of(method), Path.of("Service.java"), "process");
        });
        assertTrue(out.contains("\"signature\":"));
        assertTrue(out.contains("\"className\":\"Service\""));
        assertTrue(out.contains("\"file\":\"Service.java\""));
        assertTrue(out.contains("\"startLine\":10"));
        assertFalse(out.contains("\"modifiers\""), "Should omit modifiers in signature-only mode");
        assertFalse(out.contains("\"parameters\""), "Should omit parameters array in signature-only mode");
        assertFalse(out.contains("\"returnType\""), "Should omit returnType in signature-only mode");
        assertFalse(out.contains("\"annotations\""), "Should omit annotations in signature-only mode");
    }

    @Test
    @DisplayName("JsonFormatter without signatureOnly should emit full fields")
    void jsonFullOutput() {
        String out = captureOutput(() -> {
            JsonFormatter fmt = new JsonFormatter();
            MethodInfo method = MethodInfo.basic("greet", "Hello", 5, 10,
                    "void", List.of("public"), List.of(), "public void greet() {}");
            fmt.printMethods(List.of(method), Path.of("Hello.java"), "greet");
        });
        assertTrue(out.contains("\"modifiers\""));
        assertTrue(out.contains("\"returnType\""));
        assertTrue(out.contains("\"parameters\""));
    }

    @Test
    @DisplayName("TextFormatter with signatureOnly should print only signatures")
    void textSignatureOnly() {
        String out = captureOutput(() -> {
            TextFormatter fmt = new TextFormatter(true);
            MethodInfo method = new MethodInfo("process", "Service", 10, 50,
                    "String", List.of("public"),
                    List.of(new ParameterInfo("int", "id")),
                    "public String process(int id) { return null; }",
                    List.of(), List.of("IOException"), List.of(), null);
            fmt.printMethods(List.of(method), Path.of("Service.java"), "process");
        });
        assertTrue(out.contains("public String process(int id) throws IOException"));
        assertFalse(out.contains("Annotations:"));
        assertFalse(out.contains("Javadoc:"));
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
