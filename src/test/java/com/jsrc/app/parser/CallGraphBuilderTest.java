package com.jsrc.app.parser;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.jsrc.app.parser.model.MethodCall;
import com.jsrc.app.parser.model.MethodReference;

class CallGraphBuilderTest {

    private CallGraphBuilder builder;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        builder = new CallGraphBuilder();
    }

    @Test
    @DisplayName("Should detect direct method call within same class")
    void shouldDetectIntraClassCall() throws IOException {
        Path file = writeFile("Service.java", """
                public class Service {
                    public void process() {
                        validate();
                    }
                    private void validate() {}
                }
                """);

        builder.build(List.of(file));

        MethodReference process = new MethodReference("Service", "process", 0, file);
        MethodReference validate = new MethodReference("Service", "validate", 0, file);

        Set<MethodCall> callees = builder.getCalleesOf(process);
        assertTrue(callees.stream().anyMatch(c -> c.callee().equals(validate)),
                "process() should call validate()");

        Set<MethodCall> callers = builder.getCallersOf(validate);
        assertTrue(callers.stream().anyMatch(c -> c.caller().equals(process)),
                "validate() should be called by process()");
    }

    @Test
    @DisplayName("Should resolve type from local variable declaration")
    void shouldResolveLocalVariableType() throws IOException {
        Path helperFile = writeFile("Helper.java", """
                public class Helper {
                    public void doWork() {}
                }
                """);
        Path callerFile = writeFile("Caller.java", """
                public class Caller {
                    public void run() {
                        Helper h = new Helper();
                        h.doWork();
                    }
                }
                """);

        builder.build(List.of(helperFile, callerFile));

        MethodReference run = new MethodReference("Caller", "run", 0, callerFile);
        Set<MethodCall> callees = builder.getCalleesOf(run);
        assertTrue(callees.stream().anyMatch(c ->
                c.callee().className().equals("Helper") && c.callee().methodName().equals("doWork")),
                "Should resolve Helper.doWork() from local variable type");
    }

    @Test
    @DisplayName("Should resolve type from method parameter")
    void shouldResolveParameterType() throws IOException {
        Path serviceFile = writeFile("Svc.java", """
                public class Svc {
                    public void execute() {}
                }
                """);
        Path orchestratorFile = writeFile("Orch.java", """
                public class Orch {
                    public void orchestrate(Svc svc) {
                        svc.execute();
                    }
                }
                """);

        builder.build(List.of(serviceFile, orchestratorFile));

        MethodReference orch = new MethodReference("Orch", "orchestrate", 1, orchestratorFile);
        Set<MethodCall> callees = builder.getCalleesOf(orch);
        assertTrue(callees.stream().anyMatch(c ->
                c.callee().className().equals("Svc") && c.callee().methodName().equals("execute")),
                "Should resolve Svc.execute() from parameter type");
    }

    @Test
    @DisplayName("Should resolve type from class field")
    void shouldResolveFieldType() throws IOException {
        Path repoFile = writeFile("Repo.java", """
                public class Repo {
                    public void save() {}
                }
                """);
        Path controllerFile = writeFile("Ctrl.java", """
                public class Ctrl {
                    private Repo repo;
                    public void handle() {
                        repo.save();
                    }
                }
                """);

        builder.build(List.of(repoFile, controllerFile));

        MethodReference handle = new MethodReference("Ctrl", "handle", 0, controllerFile);
        Set<MethodCall> callees = builder.getCalleesOf(handle);
        assertTrue(callees.stream().anyMatch(c ->
                c.callee().className().equals("Repo") && c.callee().methodName().equals("save")),
                "Should resolve Repo.save() from field type");
    }

    @Test
    @DisplayName("Should resolve this.method() to current class")
    void shouldResolveThisCall() throws IOException {
        Path file = writeFile("Widget.java", """
                public class Widget {
                    public void init() {
                        this.configure();
                    }
                    private void configure() {}
                }
                """);

        builder.build(List.of(file));

        MethodReference init = new MethodReference("Widget", "init", 0, file);
        Set<MethodCall> callees = builder.getCalleesOf(init);
        assertTrue(callees.stream().anyMatch(c ->
                c.callee().className().equals("Widget") && c.callee().methodName().equals("configure")),
                "this.configure() should resolve to Widget.configure()");
    }

    @Test
    @DisplayName("Should resolve static method call by class name")
    void shouldResolveStaticCall() throws IOException {
        Path utilFile = writeFile("Util.java", """
                public class Util {
                    public static void log() {}
                }
                """);
        Path appFile = writeFile("Main.java", """
                public class Main {
                    public void run() {
                        Util.log();
                    }
                }
                """);

        builder.build(List.of(utilFile, appFile));

        MethodReference run = new MethodReference("Main", "run", 0, appFile);
        Set<MethodCall> callees = builder.getCalleesOf(run);
        assertTrue(callees.stream().anyMatch(c ->
                c.callee().className().equals("Util") && c.callee().methodName().equals("log")),
                "Util.log() should resolve as static call to Util class");
    }

    @Test
    @DisplayName("Should mark unresolvable calls with className '?'")
    void shouldHandleUnresolvedCalls() throws IOException {
        Path file = writeFile("Mystery.java", """
                public class Mystery {
                    public void act() {
                        getService().process();
                    }
                    private Object getService() { return null; }
                }
                """);

        builder.build(List.of(file));

        MethodReference act = new MethodReference("Mystery", "act", 0, file);
        Set<MethodCall> callees = builder.getCalleesOf(act);
        assertTrue(callees.stream().anyMatch(c -> c.callee().methodName().equals("process")),
                "Should record unresolved process() call");
    }

    @Test
    @DisplayName("Should register all methods and find by name")
    void shouldFindMethodsByName() throws IOException {
        Path file = writeFile("Multi.java", """
                public class Multi {
                    public void doIt() {}
                    public void doIt(String s) {}
                }
                """);

        builder.build(List.of(file));

        Set<MethodReference> found = builder.findMethodsByName("doIt");
        assertEquals(2, found.size(), "Should find both overloaded methods");
    }

    @Test
    @DisplayName("Should identify root methods (no callers)")
    void shouldIdentifyRoots() throws IOException {
        Path file = writeFile("App.java", """
                public class App {
                    public void main() {
                        helper();
                    }
                    private void helper() {}
                }
                """);

        builder.build(List.of(file));

        MethodReference main = new MethodReference("App", "main", 0, file);
        MethodReference helper = new MethodReference("App", "helper", 0, file);

        assertTrue(builder.isRoot(main), "main() should be a root (no callers)");
        assertFalse(builder.isRoot(helper), "helper() should not be a root (called by main)");
    }

    @Test
    @DisplayName("Should strip generics from type resolution")
    void shouldStripGenerics() throws IOException {
        Path listHolder = writeFile("Holder.java", """
                public class Holder {
                    public void add() {}
                }
                """);
        Path consumer = writeFile("Consumer.java", """
                import java.util.List;
                public class Consumer {
                    private Holder<String> holder;
                    public void consume() {
                        holder.add();
                    }
                }
                """);

        builder.build(List.of(listHolder, consumer));

        MethodReference consume = new MethodReference("Consumer", "consume", 0, consumer);
        Set<MethodCall> callees = builder.getCalleesOf(consume);
        assertTrue(callees.stream().anyMatch(c -> c.callee().className().equals("Holder")),
                "Should strip <String> from Holder<String> and resolve to Holder");
    }

    private Path writeFile(String name, String content) throws IOException {
        Path file = tempDir.resolve(name);
        Files.writeString(file, content);
        return file;
    }
}
