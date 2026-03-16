package com.jsrc.app.analysis;

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

    // ---- loadFromIndex ----

    @Test
    @DisplayName("loadFromIndex reconstructs call graph from index entries")
    void loadFromIndex() throws IOException {
        Path file = writeFile("Svc.java", """
                public class Svc {
                    public void a() { b(); }
                    public void b() {}
                }
                """);
        // Build index
        var index = new com.jsrc.app.index.CodebaseIndex();
        index.build(new com.jsrc.app.parser.HybridJavaParser(), List.of(file), tempDir, List.of());

        // Load from index
        var loaded = new CallGraphBuilder();
        loaded.loadFromIndex(index.getEntries());

        assertFalse(loaded.getAllMethods().isEmpty(), "Should have methods");
        assertFalse(loaded.findMethodsByName("a").isEmpty(), "Should find method 'a'");
        assertFalse(loaded.findMethodsByName("b").isEmpty(), "Should find method 'b'");

        // Verify edge: a -> b
        Set<MethodReference> aRefs = loaded.findMethodsByName("a");
        for (MethodReference a : aRefs) {
            Set<MethodCall> callees = loaded.getCalleesOf(a);
            assertTrue(callees.stream().anyMatch(c -> c.callee().methodName().equals("b")),
                    "Method 'a' should call 'b'");
        }
    }

    // ---- addEdge ----

    @Test
    @DisplayName("addEdge injects external edges into call graph")
    void addEdge() {
        MethodReference caller = new MethodReference("Ext", "invoke", 1, null);
        MethodReference callee = new MethodReference("Svc", "process", 1, null);
        MethodCall edge = new MethodCall(caller, callee, 42);

        builder.addEdge(edge);

        assertTrue(builder.getAllMethods().contains(caller));
        assertTrue(builder.getAllMethods().contains(callee));
        assertTrue(builder.getCalleesOf(caller).contains(edge));
        assertTrue(builder.getCallersOf(callee).contains(edge));
    }

    // ---- Constructor support ----

    @Test
    @DisplayName("Constructor body calls are included in call graph")
    void constructorCallsRegistered() throws IOException {
        Path file = writeFile("Foo.java", """
                public class Foo {
                    public Foo() { init(); }
                    private void init() {}
                }
                """);
        builder.build(List.of(file));

        Set<MethodReference> ctors = builder.findMethodsByName("Foo");
        assertFalse(ctors.isEmpty(), "Constructor should be registered as Foo.Foo");

        // Constructor should call init
        for (MethodReference ctor : ctors) {
            Set<MethodCall> callees = builder.getCalleesOf(ctor);
            assertTrue(callees.stream().anyMatch(c -> c.callee().methodName().equals("init")),
                    "Constructor should call init()");
        }
    }

    @Test
    @DisplayName("new Foo() creates edge to constructor")
    void objectCreationEdge() throws IOException {
        Path bar = writeFile("Bar.java", """
                public class Bar {
                    public Bar() {}
                }
                """);
        Path caller = writeFile("Caller.java", """
                public class Caller {
                    public void run() { new Bar(); }
                }
                """);
        builder.build(List.of(bar, caller));

        Set<MethodReference> runs = builder.findMethodsByName("run");
        assertFalse(runs.isEmpty());
        for (MethodReference run : runs) {
            Set<MethodCall> callees = builder.getCalleesOf(run);
            assertTrue(callees.stream().anyMatch(c ->
                    c.callee().className().equals("Bar") && c.callee().methodName().equals("Bar")),
                    "run() should have edge to new Bar()");
        }
    }

    // ---- countParamsInSignature ----

    @Test
    @DisplayName("loadFromIndex preserves param count from signatures")
    void loadFromIndexParamCount() throws IOException {
        Path file = writeFile("Multi.java", """
                public class Multi {
                    public void process(String s) {}
                    public void process(String s, int n) {}
                }
                """);
        var index = new com.jsrc.app.index.CodebaseIndex();
        index.build(new com.jsrc.app.parser.HybridJavaParser(), List.of(file), tempDir, List.of());

        var loaded = new CallGraphBuilder();
        loaded.loadFromIndex(index.getEntries());

        Set<MethodReference> processes = loaded.findMethodsByName("process");
        // Should have 2 distinct overloads
        assertTrue(processes.size() >= 2,
                "Should have 2 overloads of process, got: " + processes);
    }

    @Test
    @DisplayName("Overloaded callers produce separate edges in index-loaded graph")
    void overloadedCallersDistinct() throws IOException {
        Path file = writeFile("OverloadCaller.java", """
                public class OverloadCaller {
                    public void call(String s) { target(); }
                    public void call(String s, int n) { other(); }
                    private void target() {}
                    private void other() {}
                }
                """);

        // Build via index (exercises loadFromIndex + resolveRegistered)
        var index = new com.jsrc.app.index.CodebaseIndex();
        index.build(new com.jsrc.app.parser.HybridJavaParser(), List.of(file), tempDir, List.of());
        var loaded = new CallGraphBuilder();
        loaded.loadFromIndex(index.getEntries());

        // call(String) should have callee "target"
        // call(String,int) should have callee "other"
        Set<MethodReference> calls = loaded.findMethodsByName("call");
        assertTrue(calls.size() >= 2, "Should have 2 overloads of call: " + calls);

        boolean call1HasTarget = false;
        boolean call2HasOther = false;
        for (MethodReference caller : calls) {
            Set<MethodCall> callees = loaded.getCalleesOf(caller);
            for (MethodCall c : callees) {
                if (caller.parameterCount() == 1 && c.callee().methodName().equals("target")) {
                    call1HasTarget = true;
                }
                if (caller.parameterCount() == 2 && c.callee().methodName().equals("other")) {
                    call2HasOther = true;
                }
            }
        }
        assertTrue(call1HasTarget, "call(String) should call target()");
        assertTrue(call2HasOther, "call(String,int) should call other()");
    }

    private Path writeFile(String name, String content) throws IOException {
        Path file = tempDir.resolve(name);
        Files.writeString(file, content);
        return file;
    }
}
