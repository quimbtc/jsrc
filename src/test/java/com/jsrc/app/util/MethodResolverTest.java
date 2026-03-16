package com.jsrc.app.util;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.jsrc.app.parser.model.MethodInfo;
import com.jsrc.app.parser.model.MethodInfo.ParameterInfo;

class MethodResolverTest {

    @Test
    @DisplayName("Should parse simple method name")
    void shouldParseSimple() {
        var ref = MethodResolver.parse("process");
        assertEquals("process", ref.methodName());
        assertFalse(ref.hasClassName());
        assertFalse(ref.hasParamTypes());
    }

    @Test
    @DisplayName("Should parse method with param types")
    void shouldParseWithParams() {
        var ref = MethodResolver.parse("process(int,String)");
        assertEquals("process", ref.methodName());
        assertEquals(List.of("int", "String"), ref.paramTypes());
        assertFalse(ref.hasClassName());
    }

    @Test
    @DisplayName("Should parse Class.method(params)")
    void shouldParseClassMethodParams() {
        var ref = MethodResolver.parse("Service.process(int)");
        assertEquals("Service", ref.className());
        assertEquals("process", ref.methodName());
        assertEquals(List.of("int"), ref.paramTypes());
    }

    @Test
    @DisplayName("Should parse Class.method without params")
    void shouldParseClassMethod() {
        var ref = MethodResolver.parse("Service.process");
        assertEquals("Service", ref.className());
        assertEquals("process", ref.methodName());
        assertFalse(ref.hasParamTypes());
    }

    @Test
    @DisplayName("Should filter overloaded methods by param types")
    void shouldFilterByParamTypes() {
        var methods = List.of(
                method("process", List.of(new ParameterInfo("int", "id"))),
                method("process", List.of(new ParameterInfo("String", "name"))),
                method("process", List.of(new ParameterInfo("int", "id"), new ParameterInfo("String", "name")))
        );

        var ref = MethodResolver.parse("process(int)");
        var filtered = MethodResolver.filter(methods, ref);
        assertEquals(1, filtered.size());
        assertEquals(1, filtered.getFirst().parameters().size());
        assertEquals("int", filtered.getFirst().parameters().getFirst().type());
    }

    @Test
    @DisplayName("Should return all overloads when no params specified")
    void shouldReturnAllWhenNoParams() {
        var methods = List.of(
                method("process", List.of(new ParameterInfo("int", "id"))),
                method("process", List.of(new ParameterInfo("String", "name")))
        );

        var ref = MethodResolver.parse("process");
        var filtered = MethodResolver.filter(methods, ref);
        assertEquals(2, filtered.size());
    }

    @Test
    @DisplayName("Should parse empty parens as zero-arg method")
    void shouldParseEmptyParens() {
        var ref = MethodResolver.parse("process()");
        assertEquals("process", ref.methodName());
        assertTrue(ref.hasParamTypes());
        assertEquals(0, ref.paramTypes().size());
    }

    @Test
    @DisplayName("Should filter zero-arg overload with empty parens")
    void shouldFilterZeroArg() {
        var methods = List.of(
                method("process", List.of()),
                method("process", List.of(new ParameterInfo("int", "id")))
        );

        var ref = MethodResolver.parse("process()");
        var filtered = MethodResolver.filter(methods, ref);
        assertEquals(1, filtered.size());
        assertTrue(filtered.getFirst().parameters().isEmpty());
    }

    @Test
    @DisplayName("Should filter by class name")
    void shouldFilterByClass() {
        var m1 = MethodInfo.basic("process", "ServiceA", 1, 5, "void", List.of(), List.of(), "");
        var m2 = MethodInfo.basic("process", "ServiceB", 1, 5, "void", List.of(), List.of(), "");

        var ref = MethodResolver.parse("ServiceA.process");
        var filtered = MethodResolver.filter(List.of(m1, m2), ref);
        assertEquals(1, filtered.size());
        assertEquals("ServiceA", filtered.getFirst().className());
    }

    // ---- stripGenerics ----

    @Test
    @DisplayName("Parse strips simple generics from params")
    void stripSimpleGenerics() {
        var ref = MethodResolver.parse("foo(HashMap<String,Integer>,List<Foo>)");
        assertTrue(ref.hasParamTypes());
        assertEquals(List.of("HashMap", "List"), ref.paramTypes());
    }

    @Test
    @DisplayName("Parse strips nested generics from params")
    void stripNestedGenerics() {
        var ref = MethodResolver.parse("foo(Map<String,List<Integer>>,Set<Map<K,V>>)");
        assertTrue(ref.hasParamTypes());
        assertEquals(List.of("Map", "Set"), ref.paramTypes());
    }

    // ---- qualified names ----

    @Test
    @DisplayName("Parse extracts simple name from qualified class name")
    void qualifiedClassName() {
        var ref = MethodResolver.parse("com.foo.bar.MyService.process");
        assertEquals("MyService", ref.className());
        assertEquals("process", ref.methodName());
    }

    @Test
    @DisplayName("Parse qualified name with params")
    void qualifiedClassNameWithParams() {
        var ref = MethodResolver.parse("com.foo.MyService.process(String,int)");
        assertEquals("MyService", ref.className());
        assertEquals("process", ref.methodName());
        assertEquals(List.of("String", "int"), ref.paramTypes());
    }

    @Test
    @DisplayName("Parse qualified name with nested generics in params")
    void qualifiedNameWithGenerics() {
        var ref = MethodResolver.parse("com.foo.Svc.run(HashMap<String,List<Integer>>,Double)");
        assertEquals("Svc", ref.className());
        assertEquals("run", ref.methodName());
        assertEquals(List.of("HashMap", "Double"), ref.paramTypes());
    }

    private MethodInfo method(String name, List<ParameterInfo> params) {
        return MethodInfo.basic(name, "Service", 1, 10, "void", List.of("public"), params, "");
    }
}
