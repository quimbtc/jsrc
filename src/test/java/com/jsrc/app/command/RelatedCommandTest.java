package com.jsrc.app.command;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.jsrc.app.index.CodebaseIndex;
import com.jsrc.app.index.IndexedCodebase;
import com.jsrc.app.output.JsonFormatter;
import com.jsrc.app.output.JsonReader;
import com.jsrc.app.parser.HybridJavaParser;

class RelatedCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void directDeps_includesFieldTypes() throws Exception {
        var result = run("OrderService", """
                public class OrderService {
                    private OrderRepo repo;
                    public void save() {}
                }
                """, """
                public class OrderRepo {
                    public void persist() {}
                }
                """);
        @SuppressWarnings("unchecked")
        var deps = (List<String>) result.get("directDeps");
        assertTrue(deps.contains("OrderRepo"), "Should include field type. Got: " + deps);
    }

    @Test
    void callers_includesCallingClasses() throws Exception {
        var result = run("OrderRepo", """
                public class OrderService {
                    private OrderRepo repo;
                    public void save() { repo.persist(); }
                }
                """, """
                public class OrderRepo {
                    public void persist() {}
                }
                """);
        @SuppressWarnings("unchecked")
        var callers = (List<String>) result.get("callers");
        assertTrue(callers.contains("OrderService"), "Should include caller. Got: " + callers);
    }

    @Test
    void samePackage_filtered() throws Exception {
        var result = run("A",
                "package com.app; public class A { public void run() {} }",
                "package com.app; public class B { public void go() {} }",
                "package com.other; public class C { public void fly() {} }");
        @SuppressWarnings("unchecked")
        var samePackage = (List<String>) result.get("samePackage");
        assertTrue(samePackage.contains("B"), "B is same package");
        assertFalse(samePackage.contains("C"), "C is different package");
    }

    @Test
    void ranked_orderedByScore() throws Exception {
        var result = run("Controller", """
                public class Controller {
                    private Service svc;
                    private Repo repo;
                    public void handle() { svc.run(); }
                }
                """, """
                public class Service {
                    public void run() {}
                }
                """, """
                public class Repo {
                    public void save() {}
                }
                """);
        @SuppressWarnings("unchecked")
        var ranked = (List<Map<String, Object>>) result.get("ranked");
        assertFalse(ranked.isEmpty());
        // Service has field + caller relationship, Repo only field
        String first = (String) ranked.getFirst().get("name");
        assertEquals("Service", first, "Service should rank higher (field + call). Got: " + ranked);
    }

    @Test
    void selfReference_excluded() throws Exception {
        var result = run("Svc", """
                public class Svc {
                    private Svc instance;
                    public void run() { instance.run(); }
                }
                """);
        @SuppressWarnings("unchecked")
        var deps = (List<String>) result.get("directDeps");
        assertFalse(deps.contains("Svc"), "Should not include self-reference");
    }

    @Test
    void isolatedClass_emptyLists() throws Exception {
        var result = run("Lonely", """
                public class Lonely {
                    public void alone() {}
                }
                """, """
                public class Other {
                    public void other() {}
                }
                """);
        @SuppressWarnings("unchecked")
        var deps = (List<String>) result.get("directDeps");
        @SuppressWarnings("unchecked")
        var callers = (List<String>) result.get("callers");
        assertTrue(deps.isEmpty(), "No deps for isolated class");
        assertTrue(callers.isEmpty(), "No callers for isolated class");
    }

    @Test
    void classNotFound_error() throws Exception {
        var result = run("Ghost", """
                public class Real {
                    public void x() {}
                }
                """);
        assertNotNull(result.get("error"));
    }

    // --- helpers ---

    @SuppressWarnings("unchecked")
    private Map<String, Object> run(String cls, String... sources) throws Exception {
        List<Path> files = new java.util.ArrayList<>();
        for (int i = 0; i < sources.length; i++) {
            String src = sources[i];
            String className = "Class" + i;
            int idx = src.indexOf("class ");
            if (idx >= 0) {
                className = src.substring(idx + 6).trim().split("[\\s{<]")[0];
            }
            Path file = tempDir.resolve(className + ".java");
            Files.writeString(file, src);
            files.add(file);
        }

        var parser = new HybridJavaParser();
        var index = new CodebaseIndex();
        index.build(parser, files, tempDir, List.of());
        index.save(tempDir);
        var indexed = IndexedCodebase.tryLoad(tempDir, files);

        var baos = new ByteArrayOutputStream();
        var ctx = new CommandContext(files, tempDir.toString(), null,
                new JsonFormatter(false, null, new PrintStream(baos)), indexed, parser);

        new RelatedCommand(cls).execute(ctx);
        String json = baos.toString().trim();
        if (json.isEmpty()) return Map.of();
        return (Map<String, Object>) JsonReader.parse(json);
    }
}
