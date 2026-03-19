package com.jsrc.app.analysis;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.jsrc.app.parser.HybridJavaParser;
import com.jsrc.app.parser.TreeSitterParser;
import com.jsrc.app.parser.model.CodeSmell;
import com.jsrc.app.parser.model.CodeSmell.Severity;

class CodeSmellDetectorTest {

    private HybridJavaParser parser;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        parser = new HybridJavaParser();
    }

    // ---- 1. Switch without default ----

    @Test
    @DisplayName("Should detect switch without default case")
    void shouldDetectSwitchWithoutDefault() throws IOException {
        Path file = writeFile("SwitchNoDefault.java", """
                public class SwitchNoDefault {
                    public String describe(int x) {
                        switch (x) {
                            case 1: return "one";
                            case 2: return "two";
                        }
                        return "?";
                    }
                }
                """);

        List<CodeSmell> smells = parser.detectSmells(file);
        assertTrue(smells.stream().anyMatch(s -> s.ruleId().equals("SWITCH_WITHOUT_DEFAULT")));
    }

    @Test
    @DisplayName("Should NOT flag switch with default case")
    void shouldNotFlagSwitchWithDefault() throws IOException {
        Path file = writeFile("SwitchOk.java", """
                public class SwitchOk {
                    public String describe(int x) {
                        switch (x) {
                            case 1: return "one";
                            default: return "other";
                        }
                    }
                }
                """);

        List<CodeSmell> smells = parser.detectSmells(file);
        assertTrue(smells.stream().noneMatch(s -> s.ruleId().equals("SWITCH_WITHOUT_DEFAULT")));
    }

    // ---- 2. Empty catch block ----

    @Test
    @DisplayName("Should detect empty catch block")
    void shouldDetectEmptyCatch() throws IOException {
        Path file = writeFile("EmptyCatch.java", """
                public class EmptyCatch {
                    public void risky() {
                        try {
                            int x = 1 / 0;
                        } catch (Exception e) {
                        }
                    }
                }
                """);

        List<CodeSmell> smells = parser.detectSmells(file);
        assertTrue(smells.stream().anyMatch(s -> s.ruleId().equals("EMPTY_CATCH_BLOCK")));
    }

    @Test
    @DisplayName("Should NOT flag catch with statements")
    void shouldNotFlagNonEmptyCatch() throws IOException {
        Path file = writeFile("GoodCatch.java", """
                public class GoodCatch {
                    public void risky() {
                        try {
                            int x = 1 / 0;
                        } catch (ArithmeticException e) {
                            System.err.println(e.getMessage());
                        }
                    }
                }
                """);

        List<CodeSmell> smells = parser.detectSmells(file);
        assertTrue(smells.stream().noneMatch(s -> s.ruleId().equals("EMPTY_CATCH_BLOCK")));
    }

    // ---- 3. Catch generic exception ----

    @Test
    @DisplayName("Should detect catching generic Exception")
    void shouldDetectCatchGenericException() throws IOException {
        Path file = writeFile("GenericCatch.java", """
                public class GenericCatch {
                    public void work() {
                        try {
                            doSomething();
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                    private void doSomething() {}
                }
                """);

        List<CodeSmell> smells = parser.detectSmells(file);
        assertTrue(smells.stream().anyMatch(s -> s.ruleId().equals("CATCH_GENERIC_EXCEPTION")));
    }

    @Test
    @DisplayName("Should NOT flag catching specific exception")
    void shouldNotFlagSpecificCatch() throws IOException {
        Path file = writeFile("SpecificCatch.java", """
                public class SpecificCatch {
                    public void work() {
                        try {
                            int x = Integer.parseInt("abc");
                        } catch (NumberFormatException e) {
                            System.err.println("bad number");
                        }
                    }
                }
                """);

        List<CodeSmell> smells = parser.detectSmells(file);
        assertTrue(smells.stream().noneMatch(s -> s.ruleId().equals("CATCH_GENERIC_EXCEPTION")));
    }

    // ---- 4. Empty if body ----

    @Test
    @DisplayName("Should detect empty if body")
    void shouldDetectEmptyIfBody() throws IOException {
        Path file = writeFile("EmptyIf.java", """
                public class EmptyIf {
                    public void check(boolean flag) {
                        if (flag) {
                        }
                    }
                }
                """);

        List<CodeSmell> smells = parser.detectSmells(file);
        assertTrue(smells.stream().anyMatch(s -> s.ruleId().equals("EMPTY_IF_BODY")));
    }

    // ---- 5. Method too long ----

    @Test
    @DisplayName("Should detect method too long")
    void shouldDetectMethodTooLong() throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("public class LongMethod {\n");
        sb.append("    public void veryLong() {\n");
        for (int i = 0; i < 35; i++) {
            sb.append("        System.out.println(").append(i).append(");\n");
        }
        sb.append("    }\n}\n");

        Path file = writeFile("LongMethod.java", sb.toString());
        List<CodeSmell> smells = parser.detectSmells(file);
        assertTrue(smells.stream().anyMatch(s -> s.ruleId().equals("METHOD_TOO_LONG")));
    }

    @Test
    @DisplayName("Should NOT flag short method")
    void shouldNotFlagShortMethod() throws IOException {
        Path file = writeFile("ShortMethod.java", """
                public class ShortMethod {
                    public int add(int a, int b) {
                        return a + b;
                    }
                }
                """);

        List<CodeSmell> smells = parser.detectSmells(file);
        assertTrue(smells.stream().noneMatch(s -> s.ruleId().equals("METHOD_TOO_LONG")));
    }

    // ---- 6. Too many parameters ----

    @Test
    @DisplayName("Should detect too many parameters")
    void shouldDetectTooManyParams() throws IOException {
        Path file = writeFile("ManyParams.java", """
                public class ManyParams {
                    public void configure(int a, int b, int c, int d, int e, int f) {
                        System.out.println(a + b + c + d + e + f);
                    }
                }
                """);

        List<CodeSmell> smells = parser.detectSmells(file);
        assertTrue(smells.stream().anyMatch(s -> s.ruleId().equals("TOO_MANY_PARAMETERS")));
    }

    @Test
    @DisplayName("Should NOT flag method with few parameters")
    void shouldNotFlagFewParams() throws IOException {
        Path file = writeFile("FewParams.java", """
                public class FewParams {
                    public int add(int a, int b) {
                        return a + b;
                    }
                }
                """);

        List<CodeSmell> smells = parser.detectSmells(file);
        assertTrue(smells.stream().noneMatch(s -> s.ruleId().equals("TOO_MANY_PARAMETERS")));
    }

    // ---- 7. Deep nesting ----

    @Test
    @DisplayName("Should detect deep nesting")
    void shouldDetectDeepNesting() throws IOException {
        Path file = writeFile("DeepNest.java", """
                public class DeepNest {
                    public void deep(boolean a, boolean b, boolean c, boolean d, boolean e) {
                        if (a) {
                            if (b) {
                                if (c) {
                                    if (d) {
                                        if (e) {
                                            System.out.println("deep");
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                """);

        List<CodeSmell> smells = parser.detectSmells(file);
        assertTrue(smells.stream().anyMatch(s -> s.ruleId().equals("DEEP_NESTING")));
    }

    // ---- 8. Magic numbers ----

    @Test
    @DisplayName("Should detect magic numbers")
    void shouldDetectMagicNumbers() throws IOException {
        Path file = writeFile("MagicNum.java", """
                public class MagicNum {
                    public int compute(int x) {
                        return x * 42 + 7;
                    }
                }
                """);

        List<CodeSmell> smells = parser.detectSmells(file);
        List<CodeSmell> magic = smells.stream()
                .filter(s -> s.ruleId().equals("MAGIC_NUMBER"))
                .toList();
        assertEquals(2, magic.size());
    }

    @Test
    @DisplayName("Should NOT flag 0 and 1 as magic numbers")
    void shouldNotFlagBenignNumbers() throws IOException {
        Path file = writeFile("Benign.java", """
                public class Benign {
                    public int identity(int x) {
                        int y = 0;
                        return x * 1;
                    }
                }
                """);

        List<CodeSmell> smells = parser.detectSmells(file);
        assertTrue(smells.stream().noneMatch(s -> s.ruleId().equals("MAGIC_NUMBER")));
    }

    // ---- 9. Unused parameters ----

    @Test
    @DisplayName("Should detect unused parameters")
    void shouldDetectUnusedParameter() throws IOException {
        Path file = writeFile("Unused.java", """
                public class Unused {
                    public void process(String used, String unused) {
                        System.out.println(used);
                    }
                }
                """);

        List<CodeSmell> smells = parser.detectSmells(file);
        List<CodeSmell> unusedSmells = smells.stream()
                .filter(s -> s.ruleId().equals("UNUSED_PARAMETER"))
                .toList();
        assertEquals(1, unusedSmells.size());
        assertTrue(unusedSmells.getFirst().message().contains("unused"));
    }

    @Test
    @DisplayName("Should NOT flag used parameters")
    void shouldNotFlagUsedParams() throws IOException {
        Path file = writeFile("AllUsed.java", """
                public class AllUsed {
                    public int add(int a, int b) {
                        return a + b;
                    }
                }
                """);

        List<CodeSmell> smells = parser.detectSmells(file);
        assertTrue(smells.stream().noneMatch(s -> s.ruleId().equals("UNUSED_PARAMETER")));
    }

    @Test
    @DisplayName("Should skip unused parameter check for @Override methods")
    void shouldSkipOverrideMethods() throws IOException {
        Path file = writeFile("OverrideUnused.java", """
                public class OverrideUnused {
                    @Override
                    public boolean equals(Object obj) {
                        return true;
                    }
                }
                """);

        List<CodeSmell> smells = parser.detectSmells(file);
        assertTrue(smells.stream().noneMatch(s -> s.ruleId().equals("UNUSED_PARAMETER")));
    }

    // ---- Integration: clean code produces no smells ----

    @Test
    @DisplayName("Clean code should produce no smells")
    void cleanCodeNoSmells() throws IOException {
        Path file = writeFile("Clean.java", """
                public class Clean {
                    public int add(int a, int b) {
                        return a + b;
                    }

                    public String greet(String name) {
                        if (name == null) {
                            return "Hello!";
                        }
                        return "Hello, " + name;
                    }
                }
                """);

        List<CodeSmell> smells = parser.detectSmells(file);
        assertTrue(smells.isEmpty(), "Clean code should have no smells but got: " + smells);
    }

    // ---- Integration: TreeSitterParser returns empty ----

    @Test
    @DisplayName("TreeSitterParser.detectSmells should return empty")
    void treeSitterReturnsEmpty() throws IOException {
        TreeSitterParser tsParser = new TreeSitterParser("java");
        Path file = writeFile("Any.java", """
                public class Any {
                    public void broken() {
                        try { int x = 1/0; } catch (Exception e) {}
                    }
                }
                """);
        assertTrue(tsParser.detectSmells(file).isEmpty());
    }

    // ---- Integration: multiple smells in one file ----

    @Test
    @DisplayName("Should detect multiple smells in a single file")
    void shouldDetectMultipleSmells() throws IOException {
        Path file = writeFile("Multi.java", """
                public class Multi {
                    public void bad() {
                        try {
                            int x = 1 / 0;
                        } catch (Exception e) {
                        }

                        switch (42) {
                            case 1: break;
                        }

                        if (true) {
                        }
                    }
                }
                """);

        List<CodeSmell> smells = parser.detectSmells(file);
        assertTrue(smells.stream().anyMatch(s -> s.ruleId().equals("EMPTY_CATCH_BLOCK")));
        assertTrue(smells.stream().anyMatch(s -> s.ruleId().equals("CATCH_GENERIC_EXCEPTION")));
        assertTrue(smells.stream().anyMatch(s -> s.ruleId().equals("SWITCH_WITHOUT_DEFAULT")));
        assertTrue(smells.stream().anyMatch(s -> s.ruleId().equals("EMPTY_IF_BODY")));
        assertTrue(smells.size() >= 4);
    }

    // ---- Severity levels ----

    @Test
    @DisplayName("Smells should have appropriate severity")
    void shouldHaveCorrectSeverity() throws IOException {
        Path file = writeFile("Severity.java", """
                public class Severity {
                    public void work(int a, int b, int c, int d, int e, int f) {
                        try {
                            int x = 1 / 0;
                        } catch (Exception ex) {
                        }
                        System.out.println(a + b + c + d + e + f);
                    }
                }
                """);

        List<CodeSmell> smells = parser.detectSmells(file);
        assertTrue(smells.stream()
                .filter(s -> s.ruleId().equals("EMPTY_CATCH_BLOCK"))
                .allMatch(s -> s.severity() == Severity.WARNING));
        assertTrue(smells.stream()
                .filter(s -> s.ruleId().equals("TOO_MANY_PARAMETERS"))
                .allMatch(s -> s.severity() == Severity.INFO));
    }

    private Path writeFile(String name, String content) throws IOException {
        Path file = tempDir.resolve(name);
        Files.writeString(file, content);
        return file;
    }

    // ---- Silent failure detection ----

    @Test
    @DisplayName("Should detect catch with only continue")
    void shouldDetectCatchContinue() throws IOException {
        Path file = writeFile("ContinueCatch.java", """
                public class ContinueCatch {
                    public void process(java.util.List<String> items) {
                        for (String item : items) {
                            try {
                                Integer.parseInt(item);
                            } catch (NumberFormatException e) {
                                continue;
                            }
                        }
                    }
                }
                """);
        var smells = parser.detectSmells(file);
        assertTrue(smells.stream().anyMatch(s -> s.ruleId().equals("SILENT_CATCH_CONTINUE")),
                "Should detect catch with only continue. Got: " + smells);
    }

    @Test
    @DisplayName("Should detect catch with return null")
    void shouldDetectCatchReturnNull() throws IOException {
        Path file = writeFile("ReturnNullCatch.java", """
                public class ReturnNullCatch {
                    public String load(String path) {
                        try {
                            return new String("data");
                        } catch (Exception e) {
                            return null;
                        }
                    }
                }
                """);
        var smells = parser.detectSmells(file);
        assertTrue(smells.stream().anyMatch(s -> s.ruleId().equals("SILENT_CATCH_RETURN_NULL")),
                "Should detect catch returning null. Got: " + smells);
    }

    @Test
    @DisplayName("Should detect catch with only printStackTrace")
    void shouldDetectPrintStackTrace() throws IOException {
        Path file = writeFile("PrintStackCatch.java", """
                public class PrintStackCatch {
                    public void run() {
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                }
                """);
        var smells = parser.detectSmells(file);
        assertTrue(smells.stream().anyMatch(s -> s.ruleId().equals("CATCH_PRINT_STACKTRACE")),
                "Should detect printStackTrace. Got: " + smells);
    }

    @Test
    @DisplayName("Should detect printStackTrace + continue combo")
    void shouldDetectPrintStackTraceWithContinue() throws IOException {
        Path file = writeFile("ComboA.java", """
                public class ComboA {
                    public void process(java.util.List<String> items) {
                        for (String item : items) {
                            try {
                                Integer.parseInt(item);
                            } catch (NumberFormatException e) {
                                e.printStackTrace();
                                continue;
                            }
                        }
                    }
                }
                """);
        var smells = parser.detectSmells(file);
        assertTrue(smells.stream().anyMatch(s -> s.ruleId().equals("CATCH_PRINT_STACKTRACE")),
                "Should detect printStackTrace in combo. Got: " + smells);
        assertTrue(smells.stream().anyMatch(s -> s.ruleId().equals("SILENT_CATCH_CONTINUE")),
                "Should detect continue in combo. Got: " + smells);
    }

    @Test
    @DisplayName("Should detect printStackTrace + return null combo")
    void shouldDetectPrintStackTraceWithReturnNull() throws IOException {
        Path file = writeFile("ComboB.java", """
                public class ComboB {
                    public String load() {
                        try {
                            return "data";
                        } catch (Exception e) {
                            e.printStackTrace();
                            return null;
                        }
                    }
                }
                """);
        var smells = parser.detectSmells(file);
        assertTrue(smells.stream().anyMatch(s -> s.ruleId().equals("CATCH_PRINT_STACKTRACE")),
                "Should detect printStackTrace. Got: " + smells);
        assertTrue(smells.stream().anyMatch(s -> s.ruleId().equals("SILENT_CATCH_RETURN_NULL")),
                "Should detect return null. Got: " + smells);
    }

    @Test
    @DisplayName("Should NOT flag if catch has real handling alongside printStackTrace")
    void shouldNotFlagIfRealHandlingPresent() throws IOException {
        Path file = writeFile("RealHandling.java", """
                public class RealHandling {
                    public void process() {
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                            throw new RuntimeException("Failed", e);
                        }
                    }
                }
                """);
        var smells = parser.detectSmells(file);
        assertTrue(smells.stream().noneMatch(s -> s.ruleId().equals("CATCH_PRINT_STACKTRACE")),
                "Should not flag — has real handling (rethrow). Got: " + smells);
    }

    @Test
    @DisplayName("Should flag catch with ONLY logging as INFO")
    void shouldFlagCatchWithOnlyLogging() throws IOException {
        Path file = writeFile("LoggingCatch.java", """
                public class LoggingCatch {
                    public void run() {
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            System.err.println("Interrupted: " + e.getMessage());
                        }
                    }
                }
                """);
        var smells = parser.detectSmells(file);
        assertTrue(smells.stream().anyMatch(s -> s.ruleId().equals("SILENT_CATCH")),
                "Catch with only logging should be flagged as SILENT_CATCH. Got: " + smells);
    }

    @Test
    @DisplayName("Should flag catch with logging + return null")
    void shouldFlagCatchWithLoggingAndReturnNull() throws IOException {
        Path file = writeFile("LogReturnNull.java", """
                public class LogReturnNull {
                    public String load() {
                        try {
                            return "data";
                        } catch (Exception e) {
                            System.err.println("Error: " + e);
                            return null;
                        }
                    }
                }
                """);
        var smells = parser.detectSmells(file);
        assertTrue(smells.stream().anyMatch(s -> s.ruleId().equals("SILENT_CATCH_RETURN_NULL")),
                "Log + return null should flag SILENT_CATCH_RETURN_NULL. Got: " + smells);
    }

    @Test
    @DisplayName("Should flag catch with logging + continue")
    void shouldFlagCatchWithLoggingAndContinue() throws IOException {
        Path file = writeFile("LogContinue.java", """
                public class LogContinue {
                    public void process(java.util.List<String> items) {
                        for (String item : items) {
                            try {
                                Integer.parseInt(item);
                            } catch (NumberFormatException e) {
                                System.err.println("Bad: " + item);
                                continue;
                            }
                        }
                    }
                }
                """);
        var smells = parser.detectSmells(file);
        assertTrue(smells.stream().anyMatch(s -> s.ruleId().equals("SILENT_CATCH_CONTINUE")),
                "Log + continue should flag SILENT_CATCH_CONTINUE. Got: " + smells);
    }

    @Test
    @DisplayName("Should NOT flag catch with logging + rethrow")
    void shouldNotFlagCatchWithLoggingAndRethrow() throws IOException {
        Path file = writeFile("LogRethrow.java", """
                public class LogRethrow {
                    public void run() {
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            System.err.println("Failed: " + e.getMessage());
                            throw new RuntimeException(e);
                        }
                    }
                }
                """);
        var smells = parser.detectSmells(file);
        assertTrue(smells.stream().noneMatch(s ->
                        s.ruleId().equals("CATCH_LOG_ONLY")
                                || s.ruleId().equals("CATCH_PRINT_STACKTRACE")),
                "Log + rethrow = real handling, not silent. Got: " + smells);
    }
}
