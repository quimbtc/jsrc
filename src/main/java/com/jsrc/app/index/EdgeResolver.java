package com.jsrc.app.index;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.ThisExpr;

/**
 * Extracts and resolves call edges from Java source files.
 * <p>
 * Responsibilities:
 * <ul>
 *   <li>Extract direct, reflective, and constructor call edges from source</li>
 *   <li>Resolve callee class using local variables, parameters, fields, and class names</li>
 *   <li>Produce {@code ?field:} and {@code ?ret:} markers for cross-class resolution</li>
 *   <li>Post-build marker resolution using field type and return type maps</li>
 * </ul>
 */
public class EdgeResolver {

    private static final Logger logger = LoggerFactory.getLogger(EdgeResolver.class);

    /**
     * Extracts call edges from a Java file using JavaParser.
     * Resolves callee class names using field types, parameter types,
     * and local variable types for accurate call graph edges.
     */
    public List<CallEdge> extractCallEdges(Path file, JavaParser jp) {
        List<CallEdge> edges = new ArrayList<>();
        try {
            String source = Files.readString(file);
            var result = jp.parse(source);
            if (!result.getResult().isPresent()) return edges;

            CompilationUnit cu = result.getResult().get();
            for (ClassOrInterfaceDeclaration cid : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                String className = cid.getNameAsString();

                Map<String, String> fieldTypes = new HashMap<>();
                for (FieldDeclaration field : cid.getFields()) {
                    String fieldType = field.getCommonType().asString();
                    int genIdx = fieldType.indexOf('<');
                    if (genIdx > 0) fieldType = fieldType.substring(0, genIdx);
                    for (VariableDeclarator var : field.getVariables()) {
                        fieldTypes.put(var.getNameAsString(), fieldType);
                    }
                }

                for (MethodDeclaration md : cid.getMethods()) {
                    extractEdgesFromCallable(edges, md, className, md.getNameAsString(),
                            md.getParameters().size(), fieldTypes);
                }
                for (ConstructorDeclaration cd : cid.getConstructors()) {
                    extractEdgesFromCallable(edges, cd, className, className,
                            cd.getParameters().size(), fieldTypes);
                }
            }
        } catch (Exception ex) {
            logger.debug("Error extracting call edges from {}: {}", file, ex.getMessage());
        }
        return edges;
    }

    /**
     * Extracts reflective call edges based on invoker config.
     * E.g. ejecutarMetodo("calcularImporte", ...) → CallerAdaptadorBean.calcularImporte()
     */
    public List<CallEdge> extractReflectiveEdges(Path file, JavaParser jp,
                                                  List<com.jsrc.app.config.ArchitectureConfig.InvokerDef> invokers) {
        List<CallEdge> edges = new ArrayList<>();
        try {
            String source = Files.readString(file);
            var result = jp.parse(source);
            if (!result.getResult().isPresent()) return edges;

            CompilationUnit cu = result.getResult().get();
            Map<String, com.jsrc.app.config.ArchitectureConfig.InvokerDef> invokerMap = new HashMap<>();
            for (var inv : invokers) {
                invokerMap.put(inv.method(), inv);
            }

            for (ClassOrInterfaceDeclaration cid : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                String callerClass = cid.getNameAsString();
                for (MethodDeclaration md : cid.getMethods()) {
                    for (MethodCallExpr call : md.findAll(MethodCallExpr.class)) {
                        var inv = invokerMap.get(call.getNameAsString());
                        if (inv == null) continue;
                        if (call.getArguments().size() <= inv.targetArg()) continue;
                        var arg = call.getArguments().get(inv.targetArg());
                        if (!(arg instanceof com.github.javaparser.ast.expr.StringLiteralExpr strLit)) continue;

                        String targetMethod = strLit.getValue();
                        String prefix = callerClass;
                        for (String suffix : inv.callerSuffixes()) {
                            if (prefix.endsWith(suffix)) {
                                prefix = prefix.substring(0, prefix.length() - suffix.length());
                                break;
                            }
                        }
                        String convention = inv.resolveClass();
                        String targetClass = prefix + convention.substring(0, 1).toUpperCase()
                                + convention.substring(1);

                        int line = call.getBegin().map(p -> p.line).orElse(-1);
                        edges.add(new CallEdge(callerClass, md.getNameAsString(),
                                targetClass, targetMethod, line));
                    }
                }
            }
        } catch (Exception ex) {
            logger.debug("Error extracting reflective edges from {}: {}", file, ex.getMessage());
        }
        return edges;
    }

    /**
     * Resolves {@code ?field:} and {@code ?ret:} callee class markers in index entries.
     * Uses field type and return type information from all indexed classes.
     * <p>
     * Modifies the entries list in place, replacing entries whose edges changed.
     * Runs iteratively (up to 5 passes) for nested marker chains.
     */
    public void resolveMarkers(List<IndexEntry> entries) {
        Map<String, String> fieldTypeMap = new HashMap<>();
        Map<String, String> returnTypeMap = new HashMap<>();
        for (IndexEntry entry : entries) {
            for (IndexedClass ic : entry.classes()) {
                for (IndexedField f : ic.fields()) {
                    fieldTypeMap.put(ic.name() + "." + f.name(), f.type());
                }
                for (IndexedMethod im : ic.methods()) {
                    if (im.returnType() != null && !im.returnType().isEmpty()
                            && !"void".equals(im.returnType())) {
                        String rt = im.returnType();
                        int genIdx = rt.indexOf('<');
                        if (genIdx > 0) rt = rt.substring(0, genIdx);
                        returnTypeMap.put(ic.name() + "." + im.name(), rt);
                    }
                }
            }
        }
        if (fieldTypeMap.isEmpty() && returnTypeMap.isEmpty()) return;

        for (int pass = 0; pass < 5; pass++) {
            boolean changed = false;
            List<IndexEntry> newEntries = new ArrayList<>();
            for (IndexEntry entry : entries) {
                List<CallEdge> newEdges = new ArrayList<>();
                boolean entryChanged = false;
                for (CallEdge edge : entry.callEdges()) {
                    if (edge.calleeClass().startsWith("?field:")
                            || edge.calleeClass().startsWith("?ret:")) {
                        String resolved = resolveMarker(edge.calleeClass(),
                                fieldTypeMap, returnTypeMap);
                        if (resolved != null && !resolved.startsWith("?")) {
                            newEdges.add(new CallEdge(edge.callerClass(), edge.callerMethod(),
                                    edge.callerParamCount(), resolved, edge.calleeMethod(),
                                    edge.line(), edge.argCount()));
                            entryChanged = true;
                            changed = true;
                            continue;
                        }
                    }
                    newEdges.add(edge);
                }
                newEntries.add(entryChanged
                        ? new IndexEntry(entry.path(), entry.contentHash(),
                                entry.lastModified(), entry.classes(), newEdges)
                        : entry);
            }
            entries.clear();
            entries.addAll(newEntries);
            if (!changed) break;
        }
    }

    // ---- internal ----

    private static void extractEdgesFromCallable(List<CallEdge> edges,
                                                  com.github.javaparser.ast.body.CallableDeclaration<?> callable,
                                                  String className, String callerMethod,
                                                  int callerParamCount,
                                                  Map<String, String> fieldTypes) {
        Map<String, String> localTypes = new HashMap<>();
        for (Parameter param : callable.getParameters()) {
            String pType = param.getTypeAsString();
            int gi = pType.indexOf('<');
            if (gi > 0) pType = pType.substring(0, gi);
            localTypes.put(param.getNameAsString(), pType);
        }
        for (VariableDeclarator var : callable.findAll(VariableDeclarator.class)) {
            var parent = var.getParentNode().orElse(null);
            if (parent != null && !(parent instanceof FieldDeclaration)) {
                String vType = var.getTypeAsString();
                int gi = vType.indexOf('<');
                if (gi > 0) vType = vType.substring(0, gi);
                localTypes.put(var.getNameAsString(), vType);
            }
        }
        for (MethodCallExpr call : callable.findAll(MethodCallExpr.class)) {
            String calleeMethod = call.getNameAsString();
            String calleeClass = resolveCalleeClass(call, className, fieldTypes, localTypes);
            int line = call.getBegin().map(p -> p.line).orElse(-1);
            int argCount = call.getArguments().size();
            edges.add(new CallEdge(className, callerMethod, callerParamCount,
                    calleeClass, calleeMethod, line, argCount));
        }
        for (ObjectCreationExpr newExpr : callable.findAll(ObjectCreationExpr.class)) {
            String targetClass = newExpr.getType().getNameAsString();
            int line = newExpr.getBegin().map(p -> p.line).orElse(-1);
            int argCount = newExpr.getArguments().size();
            edges.add(new CallEdge(className, callerMethod, callerParamCount,
                    targetClass, targetClass, line, argCount));
        }
    }

    /**
     * Resolves the class of the callee in a method call expression.
     * Checks: this → current class, variable → local/param/field type,
     * FieldAccessExpr → field type lookup with marker encoding, static → class name.
     */
    static String resolveCalleeClass(MethodCallExpr call, String currentClass,
                                      Map<String, String> fieldTypes,
                                      Map<String, String> localTypes) {
        if (call.getScope().isEmpty()) return currentClass;
        var scope = call.getScope().get();
        if (scope instanceof ThisExpr) return currentClass;
        if (scope instanceof NameExpr ne) {
            String varName = ne.getNameAsString();
            String type = localTypes.get(varName);
            if (type != null) return type;
            type = fieldTypes.get(varName);
            if (type != null) return type;
            return varName;
        }
        if (scope instanceof FieldAccessExpr fae) {
            String fieldName = fae.getNameAsString();
            var objExpr = fae.getScope();
            String objType = resolveExpressionType(objExpr, currentClass, fieldTypes, localTypes);
            if (objType != null) {
                return "?field:" + objType + "." + fieldName;
            }
        }
        return "?";
    }

    /**
     * Resolves the type of an expression (variable, this, field access, method call).
     * Produces {@code ?field:} and {@code ?ret:} markers for deferred resolution.
     */
    static String resolveExpressionType(Expression expr, String currentClass,
                                         Map<String, String> fieldTypes,
                                         Map<String, String> localTypes) {
        if (expr instanceof ThisExpr) return currentClass;
        if (expr instanceof NameExpr ne) {
            String varName = ne.getNameAsString();
            String type = localTypes.get(varName);
            if (type != null) return type;
            type = fieldTypes.get(varName);
            if (type != null) return type;
            return varName;
        }
        if (expr instanceof FieldAccessExpr fae) {
            String objType = resolveExpressionType(fae.getScope(), currentClass,
                    fieldTypes, localTypes);
            if (objType != null) {
                return "?field:" + objType + "." + fae.getNameAsString();
            }
        }
        if (expr instanceof MethodCallExpr mce) {
            String methodName = mce.getNameAsString();
            if (mce.getScope().isEmpty()) {
                return "?ret:" + currentClass + "." + methodName;
            }
            String scopeType = resolveExpressionType(mce.getScope().get(), currentClass,
                    fieldTypes, localTypes);
            if (scopeType != null) {
                return "?ret:" + scopeType + "." + methodName;
            }
        }
        return null;
    }

    /**
     * Resolves a marker string to a concrete type.
     * Supports nested {@code ?field:OwnerType.fieldName} and {@code ?ret:ClassName.methodName}.
     */
    public static String resolveMarker(String marker,
                                       Map<String, String> fieldTypeMap,
                                       Map<String, String> returnTypeMap) {
        if (marker.startsWith("?field:")) {
            String payload = marker.substring("?field:".length());
            int dotIdx = payload.lastIndexOf('.');
            if (dotIdx < 0) return null;

            String ownerType = payload.substring(0, dotIdx);
            String fieldName = payload.substring(dotIdx + 1);

            if (ownerType.startsWith("?")) {
                ownerType = resolveMarker(ownerType, fieldTypeMap, returnTypeMap);
                if (ownerType == null || ownerType.startsWith("?")) return null;
            }

            return fieldTypeMap.get(ownerType + "." + fieldName);
        }

        if (marker.startsWith("?ret:")) {
            String payload = marker.substring("?ret:".length());
            int dotIdx = payload.lastIndexOf('.');
            if (dotIdx < 0) return null;

            String ownerType = payload.substring(0, dotIdx);
            String methodName = payload.substring(dotIdx + 1);

            if (ownerType.startsWith("?")) {
                ownerType = resolveMarker(ownerType, fieldTypeMap, returnTypeMap);
                if (ownerType == null || ownerType.startsWith("?")) return null;
            }

            return returnTypeMap.get(ownerType + "." + methodName);
        }

        return marker;
    }
}
