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

/**
 * Tests that --patterns auto-discovers naming patterns like
 * Detail→DetailBean→DAO, and --snippet can extract templates for them.
 */
class LayerChainDetectionTest {

    @TempDir
    Path tempDir;

    @Test
    void extractSuffix_standardPatterns() {
        assertEquals("Service", PatternsCommand.extractSuffix("OrderService"));
        assertEquals("Controller", PatternsCommand.extractSuffix("OrderController"));
        assertEquals("DetailBean", PatternsCommand.extractSuffix("FacturaDetailBean"));
        assertEquals("Detail", PatternsCommand.extractSuffix("FacturaDetail"));
        assertEquals("DAO", PatternsCommand.extractSuffix("FacturaDAO"));
        assertNull(PatternsCommand.extractSuffix("App")); // single word
    }

    // --- Edge cases from Rune's review ---

    @Test
    void extractSuffix_uppercasePrefixThenCamelCase() {
        // XMLParser → should extract "Parser", not get confused by XML uppercase run
        String result = PatternsCommand.extractSuffix("XMLParser");
        assertEquals("Parser", result, "Should handle uppercase prefix");

        result = PatternsCommand.extractSuffix("HTMLRenderer");
        assertEquals("Renderer", result, "Should handle uppercase prefix");
    }

    @Test
    void extractSuffix_shortBaseWithUppercaseSuffix() {
        assertEquals("DAO", PatternsCommand.extractSuffix("MyDAO"));
    }

    @Test
    void extractSuffix_entireNameIsSuffix() {
        // "DAO" alone — no base name, should return null
        assertNull(PatternsCommand.extractSuffix("DAO"), "Whole name = suffix → null");
        assertNull(PatternsCommand.extractSuffix("SERVICE"), "All uppercase → null");
    }

    @Test
    void extractSuffix_allLowercase() {
        assertNull(PatternsCommand.extractSuffix("service"), "All lowercase → null");
    }

    @Test
    void extractSuffix_withNumbers() {
        String result = PatternsCommand.extractSuffix("OAuth2Handler");
        assertEquals("Handler", result, "Should handle numbers in name");
    }

    @Test
    void extractSuffix_trivialInputs() {
        assertNull(PatternsCommand.extractSuffix("A"));
        assertNull(PatternsCommand.extractSuffix(""));
        assertNull(PatternsCommand.extractSuffix(null));
    }

    @Test
    void extractSuffix_serviceImpl() {
        String result = PatternsCommand.extractSuffix("OrderServiceImpl");
        // Should extract "ServiceImpl" or "Impl" — either is valid
        assertNotNull(result);
        assertTrue(result.contains("Impl"), "Should include Impl. Got: " + result);
    }

    @Test
    void patterns_discoversCustomSuffixes() throws Exception {
        var result = runPatterns(
                "public class FacturaDetail { public void show() {} }",
                "public class ClienteDetail { public void show() {} }",
                "public class ProductoDetail { public void show() {} }",
                "public class FacturaDetailBean { public void save() {} }",
                "public class ClienteDetailBean { public void save() {} }",
                "public class ProductoDetailBean { public void save() {} }",
                "public class FacturaDAO { public void persist() {} }",
                "public class ClienteDAO { public void persist() {} }",
                "public class ProductoDAO { public void persist() {} }");

        @SuppressWarnings("unchecked")
        var naming = (Map<String, Object>) result.get("naming");
        @SuppressWarnings("unchecked")
        var suffixes = (Map<String, ?>) naming.get("classSuffixes");

        assertTrue(suffixes.containsKey("Detail"), "Should discover Detail. Got: " + suffixes);
        assertTrue(suffixes.containsKey("DetailBean"), "Should discover DetailBean. Got: " + suffixes);
        assertTrue(suffixes.containsKey("DAO"), "Should discover DAO. Got: " + suffixes);
    }

    @Test
    void patterns_detectsLayerChain() throws Exception {
        var result = runPatterns(
                "public class FacturaDetail { public void show() {} }",
                "public class ClienteDetail { public void show() {} }",
                "public class ProductoDetail { public void show() {} }",
                "public class FacturaDetailBean { public void save() {} }",
                "public class ClienteDetailBean { public void save() {} }",
                "public class ProductoDetailBean { public void save() {} }",
                "public class FacturaDAO { public void persist() {} }",
                "public class ClienteDAO { public void persist() {} }",
                "public class ProductoDAO { public void persist() {} }");

        @SuppressWarnings("unchecked")
        var chains = (List<Map<String, Object>>) result.get("layerChains");
        assertNotNull(chains, "Should detect layer chains. Full result: " + result);
        assertFalse(chains.isEmpty(), "Should have at least one chain");

        // Should detect Detail → DetailBean → DAO
        var firstChain = chains.getFirst();
        String pattern = (String) firstChain.get("pattern");
        assertTrue(pattern.contains("Detail"), "Chain should include Detail. Got: " + pattern);
        assertTrue(((Number) firstChain.get("occurrences")).intValue() >= 3,
                "Should occur 3+ times");
    }

    @Test
    void snippet_worksWithCustomSuffix() throws Exception {
        List<Path> files = new java.util.ArrayList<>();
        for (String src : new String[]{
                "public class FacturaDetailBean {\n    private final FacturaDAO dao;\n    public FacturaDetailBean(FacturaDAO dao) { this.dao = dao; }\n    public void save() {}\n}",
                "public class ClienteDetailBean {\n    private final ClienteDAO dao;\n    public ClienteDetailBean(ClienteDAO dao) { this.dao = dao; }\n    public void save() {}\n}",
                "public class FacturaDAO {\n    public void persist() {}\n}",
                "public class ClienteDAO {\n    public void persist() {}\n}"
        }) {
            String className = src.substring(src.indexOf("class ") + 6).split("[\\s{]")[0];
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

        // Should be able to create snippet for "detailbean"
        new SnippetCommand("detailbean").execute(ctx);
        @SuppressWarnings("unchecked")
        var result = (Map<String, Object>) JsonReader.parse(baos.toString().trim());

        assertNull(result.get("error"), "Should find DetailBean pattern. Got: " + result);
        String template = (String) result.get("template");
        assertTrue(template.contains("${Name}"), "Template should have placeholder");
    }

    // --- helpers ---

    @SuppressWarnings("unchecked")
    private Map<String, Object> runPatterns(String... sources) throws Exception {
        List<Path> files = new java.util.ArrayList<>();
        for (int i = 0; i < sources.length; i++) {
            String src = sources[i];
            String className = src.substring(src.indexOf("class ") + 6).split("[\\s{]")[0];
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
        new PatternsCommand().execute(ctx);
        return (Map<String, Object>) JsonReader.parse(baos.toString().trim());
    }
}
