package com.jsrc.app.command;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.jsrc.app.output.JsonFormatter;
import com.jsrc.app.output.JsonReader;
import com.jsrc.app.parser.HybridJavaParser;

class SearchCommandTest {

    @TempDir
    Path tempDir;

    private HybridJavaParser parser;

    @BeforeEach
    void setUp() {
        parser = new HybridJavaParser();
    }

    private List<Object> executeSearch(String pattern, String sourceCode) throws Exception {
        Path file = tempDir.resolve("Test.java");
        Files.writeString(file, sourceCode);

        var ctx = new CommandContext(
                List.of(file), tempDir.toString(), null,
                new JsonFormatter(), null, parser);

        var out = new ByteArrayOutputStream();
        var oldOut = System.out;
        System.setOut(new PrintStream(out));
        try {
            new SearchCommand(pattern).execute(ctx);
        } finally {
            System.setOut(oldOut);
        }

        String json = out.toString().trim();
        if (json.isEmpty()) return List.of();
        @SuppressWarnings("unchecked")
        List<Object> parsed = (List<Object>) JsonReader.parse(json);
        return parsed;
    }

    @Test
    @DisplayName("Match in code is not marked as comment")
    void matchInCode() throws Exception {
        String source = """
                package com.test;
                public class Test {
                    public void doWork() {
                        System.out.println("hello");
                    }
                }
                """;
        var results = executeSearch("doWork", source);
        assertEquals(1, results.size());
        @SuppressWarnings("unchecked")
        var match = (java.util.Map<String, Object>) results.get(0);
        assertEquals(false, match.get("inComment"));
    }

    @Test
    @DisplayName("Match in // comment is marked as comment")
    void matchInLineComment() throws Exception {
        String source = """
                package com.test;
                public class Test {
                    // doWork is deprecated
                    public void other() {}
                }
                """;
        var results = executeSearch("doWork", source);
        assertEquals(1, results.size());
        @SuppressWarnings("unchecked")
        var match = (java.util.Map<String, Object>) results.get(0);
        assertEquals(true, match.get("inComment"));
    }

    @Test
    @DisplayName("Match in /* */ block comment is marked as comment")
    void matchInBlockComment() throws Exception {
        String source = """
                package com.test;
                public class Test {
                    /*
                     * doWork was removed
                     */
                    public void other() {}
                }
                """;
        var results = executeSearch("doWork", source);
        assertEquals(1, results.size());
        @SuppressWarnings("unchecked")
        var match = (java.util.Map<String, Object>) results.get(0);
        assertEquals(true, match.get("inComment"));
    }

    @Test
    @DisplayName("Match in /** */ javadoc is marked as comment")
    void matchInJavadoc() throws Exception {
        String source = """
                package com.test;
                public class Test {
                    /**
                     * Calls doWork internally.
                     */
                    public void other() {}
                }
                """;
        var results = executeSearch("doWork", source);
        assertEquals(1, results.size());
        @SuppressWarnings("unchecked")
        var match = (java.util.Map<String, Object>) results.get(0);
        assertEquals(true, match.get("inComment"));
    }

    @Test
    @DisplayName("Match in string literal is not marked as comment")
    void matchInStringLiteral() throws Exception {
        String source = """
                package com.test;
                public class Test {
                    public void other() {
                        String s = "doWork";
                    }
                }
                """;
        var results = executeSearch("doWork", source);
        assertEquals(1, results.size());
        @SuppressWarnings("unchecked")
        var match = (java.util.Map<String, Object>) results.get(0);
        assertEquals(false, match.get("inComment"));
    }

    @Test
    @DisplayName("Match between */ and /* on same line is not comment")
    void matchBetweenBlockComments() throws Exception {
        String source = """
                package com.test;
                public class Test {
                    /* first */ void doWork() {} /* second
                     */
                }
                """;
        var results = executeSearch("doWork", source);
        assertEquals(1, results.size());
        @SuppressWarnings("unchecked")
        var match = (java.util.Map<String, Object>) results.get(0);
        assertEquals(false, match.get("inComment"));
    }

    @Test
    @DisplayName("Match after */ followed by /* on same line is in comment")
    void matchInSecondBlockComment() throws Exception {
        String source = """
                package com.test;
                public class Test {
                    /* first */ int x; /* doWork
                     */
                }
                """;
        var results = executeSearch("doWork", source);
        assertEquals(1, results.size());
        @SuppressWarnings("unchecked")
        var match = (java.util.Map<String, Object>) results.get(0);
        assertEquals(true, match.get("inComment"));
    }

    @Test
    @DisplayName("Match after block comment ends is not marked as comment")
    void matchAfterBlockComment() throws Exception {
        String source = """
                package com.test;
                public class Test {
                    /* comment */
                    public void doWork() {}
                }
                """;
        var results = executeSearch("doWork", source);
        assertEquals(1, results.size());
        @SuppressWarnings("unchecked")
        var match = (java.util.Map<String, Object>) results.get(0);
        assertEquals(false, match.get("inComment"));
    }
}
