package com.jsrc.app.architecture;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.jsrc.app.config.ArchitectureConfig;
import com.jsrc.app.config.ArchitectureConfig.*;
import com.jsrc.app.parser.HybridJavaParser;
import com.jsrc.app.parser.model.ClassInfo;

class RuleEngineTest {

    @TempDir
    Path tempDir;

    private List<ClassInfo> parseClasses(String fileName, String source) throws Exception {
        Path file = tempDir.resolve(fileName);
        Files.writeString(file, source);
        return new HybridJavaParser().parseClasses(file);
    }

    @Test
    @DisplayName("deny-import rule detects forbidden import")
    void denyImportViolation() throws Exception {
        var layers = List.of(
                new LayerDef("controller", "**/*Controller", List.of()),
                new LayerDef("repository", "**/*Repository", List.of()));
        var rules = List.of(new RuleDef("no-repo", "Controllers must not import repos",
                "controller", null, "repository", null, null));
        var config = new ArchitectureConfig(layers, rules, List.of(), List.of());
        var engine = new RuleEngine(config);

        var controllerClasses = parseClasses("OrderController.java", """
                import com.app.repository.OrderRepository;
                public class OrderController {
                    private OrderRepository repo;
                    public void list() {}
                }
                """);
        var repoClasses = parseClasses("OrderRepository.java", """
                public class OrderRepository {
                    public void findAll() {}
                }
                """);
        var allClasses = new java.util.ArrayList<>(controllerClasses);
        allClasses.addAll(repoClasses);

        var files = List.of(tempDir.resolve("OrderController.java"), tempDir.resolve("OrderRepository.java"));
        var violations = engine.evaluate(allClasses, files);
        assertFalse(violations.isEmpty(), "Should detect deny-import violation");
        assertTrue(violations.stream().anyMatch(v -> v.ruleId().equals("no-repo")));
    }

    @Test
    @DisplayName("deny-import rule passes when no forbidden import")
    void denyImportClean() throws Exception {
        var layers = List.of(
                new LayerDef("controller", "**/*Controller", List.of()),
                new LayerDef("repository", "**/*Repository", List.of()));
        var rules = List.of(new RuleDef("no-repo", "Controllers must not import repos",
                "controller", null, "repository", null, null));
        var config = new ArchitectureConfig(layers, rules, List.of(), List.of());
        var engine = new RuleEngine(config);

        var controllerClasses = parseClasses("OrderController.java", """
                import com.app.service.OrderService;
                public class OrderController {
                    private OrderService svc;
                    public void list() {}
                }
                """);
        var repoClasses = parseClasses("OrderRepository.java", """
                public class OrderRepository {}
                """);
        var allClasses = new java.util.ArrayList<>(controllerClasses);
        allClasses.addAll(repoClasses);

        var files = List.of(tempDir.resolve("OrderController.java"), tempDir.resolve("OrderRepository.java"));
        var violations = engine.evaluate(allClasses, files);
        assertTrue(violations.isEmpty(), "Should have no violations");
    }

    @Test
    @DisplayName("deny-annotation rule detects forbidden annotation on fields")
    void denyAnnotationViolation() throws Exception {
        var layers = List.of(new LayerDef("service", "**/*Service", List.of()));
        var rules = List.of(new RuleDef("no-autowired", "No field injection",
                null, "service", null, null, "Autowired"));
        var config = new ArchitectureConfig(layers, rules, List.of(), List.of());
        var engine = new RuleEngine(config);

        var classes = parseClasses("OrderService.java", """
                public class OrderService {
                    private OrderRepository repo;
                    @Autowired
                    public void setRepo(OrderRepository repo) { this.repo = repo; }
                }
                """);

        var violations = engine.evaluate(classes, List.of(tempDir.resolve("OrderService.java")));
        assertFalse(violations.isEmpty(), "Should detect @Autowired violation");
    }

    @Test
    @DisplayName("evaluateRule filters by rule ID")
    void evaluateSpecificRule() throws Exception {
        var layers = List.of(
                new LayerDef("controller", "**/*Controller", List.of()),
                new LayerDef("repository", "**/*Repository", List.of()));
        var rules = List.of(
                new RuleDef("no-repo", "No repos in controllers", "controller", null, "repository", null, null),
                new RuleDef("other-rule", "Other", null, null, null, null, null));
        var config = new ArchitectureConfig(layers, rules, List.of(), List.of());
        var engine = new RuleEngine(config);

        var controllerClasses = parseClasses("OrderController.java", """
                import com.app.repository.OrderRepository;
                public class OrderController { private OrderRepository repo; }
                """);
        var repoClasses = parseClasses("OrderRepository.java", """
                public class OrderRepository {}
                """);
        var allClasses = new java.util.ArrayList<>(controllerClasses);
        allClasses.addAll(repoClasses);

        var files = List.of(tempDir.resolve("OrderController.java"), tempDir.resolve("OrderRepository.java"));
        var violations = engine.evaluateRule("no-repo", allClasses, files);
        assertFalse(violations.isEmpty());
        assertTrue(violations.stream().allMatch(v -> v.ruleId().equals("no-repo")));
    }

    @Test
    @DisplayName("require constructor-injection detects field-only injection")
    void constructorInjectionViolation() throws Exception {
        var layers = List.of(new LayerDef("service", "**/*Service", List.of()));
        var rules = List.of(new RuleDef("ctor-inject", "Must use constructor injection",
                null, "service", null, "constructor-injection", null));
        var config = new ArchitectureConfig(layers, rules, List.of(), List.of());
        var engine = new RuleEngine(config);

        var classes = parseClasses("OrderService.java", """
                public class OrderService {
                    private OrderRepository repo;
                    public void placeOrder() {}
                }
                """);

        var files = List.of(tempDir.resolve("OrderService.java"));
        var violations = engine.evaluate(classes, files);
        assertFalse(violations.isEmpty(), "Should detect missing constructor injection");
        assertTrue(violations.stream().anyMatch(v -> v.ruleId().equals("ctor-inject")));
    }

    @Test
    @DisplayName("require constructor-injection passes with constructor deps")
    void constructorInjectionClean() throws Exception {
        var layers = List.of(new LayerDef("service", "**/*Service", List.of()));
        var rules = List.of(new RuleDef("ctor-inject", "Must use constructor injection",
                null, "service", null, "constructor-injection", null));
        var config = new ArchitectureConfig(layers, rules, List.of(), List.of());
        var engine = new RuleEngine(config);

        var classes = parseClasses("OrderService.java", """
                public class OrderService {
                    private final OrderRepository repo;
                    public OrderService(OrderRepository repo) {
                        this.repo = repo;
                    }
                    public void placeOrder() {}
                }
                """);

        var files = List.of(tempDir.resolve("OrderService.java"));
        var violations = engine.evaluate(classes, files);
        assertTrue(violations.isEmpty(), "Should have no violations with constructor injection");
    }

    @Test
    @DisplayName("require constructor-injection skips interfaces")
    void constructorInjectionSkipsInterfaces() throws Exception {
        var layers = List.of(new LayerDef("service", "**/*Service", List.of()));
        var rules = List.of(new RuleDef("ctor-inject", "Must use constructor injection",
                null, "service", null, "constructor-injection", null));
        var config = new ArchitectureConfig(layers, rules, List.of(), List.of());
        var engine = new RuleEngine(config);

        var classes = parseClasses("OrderService.java", """
                public interface OrderService {
                    void placeOrder();
                }
                """);

        var files = List.of(tempDir.resolve("OrderService.java"));
        var violations = engine.evaluate(classes, files);
        assertTrue(violations.isEmpty(), "Interfaces should not trigger constructor-injection rule");
    }
}
