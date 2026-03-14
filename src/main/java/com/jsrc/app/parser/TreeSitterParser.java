package com.jsrc.app.parser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.jsrc.app.parser.model.ClassInfo;
import com.jsrc.app.parser.model.CodeSmell;
import com.jsrc.app.parser.model.MethodInfo;
import com.jsrc.app.parser.model.MethodInfo.ParameterInfo;

import io.github.treesitter.jtreesitter.InputEncoding;
import io.github.treesitter.jtreesitter.Language;
import io.github.treesitter.jtreesitter.Node;
import io.github.treesitter.jtreesitter.Parser;
import io.github.treesitter.jtreesitter.Query;
import io.github.treesitter.jtreesitter.QueryCursor;
import io.github.treesitter.jtreesitter.Tree;

/**
 * {@link CodeParser} implementation backed by Tree-sitter for fast,
 * syntax-level discovery. Produces {@link MethodInfo#basic} results
 * without semantic fields (annotations, throws, javadoc).
 */
public class TreeSitterParser implements CodeParser {

    private static final Logger logger = LoggerFactory.getLogger(TreeSitterParser.class);

    private static final String METHOD_BY_NAME_QUERY =
            "(method_declaration name: (identifier) @methodName (#eq? @methodName \"%s\"))";
    private static final String ALL_METHODS_QUERY =
            "(method_declaration name: (identifier) @methodName)";
    private static final String ALL_CLASSES_QUERY =
            "[(class_declaration name: (identifier) @className)" +
            " (interface_declaration name: (identifier) @className)" +
            " (enum_declaration name: (identifier) @className)" +
            " (record_declaration name: (identifier) @className)]";

    private final Parser parser;
    private final Language language;
    private final String languageKey;

    @SuppressWarnings("null")
    public TreeSitterParser(String languageKey) {
        if (languageKey == null || languageKey.isBlank()) {
            throw new IllegalArgumentException("Language key must not be null or blank");
        }
        this.languageKey = languageKey.toLowerCase();
        this.language = TreeSitterLanguageFactory.getLanguage(this.languageKey);
        this.parser = new Parser();
        this.parser.setLanguage(language);
    }

    @Override
    public List<MethodInfo> findMethods(Path path, String methodName) {
        if (!isValidInput(path, methodName)) return Collections.emptyList();
        String queryString = String.format(METHOD_BY_NAME_QUERY, methodName);
        return executeMethodQuery(path, queryString, methodName);
    }

    @Override
    public List<MethodInfo> findMethods(Path path, String methodName, List<String> parameterTypes) {
        List<MethodInfo> allMatches = findMethods(path, methodName);
        if (parameterTypes == null) return allMatches;
        return allMatches.stream()
                .filter(m -> parameterTypesMatch(m.parameters(), parameterTypes))
                .toList();
    }

    @Override
    public List<MethodInfo> findAllMethods(Path path) {
        if (!isValidPath(path)) return Collections.emptyList();
        return executeMethodQuery(path, ALL_METHODS_QUERY, null);
    }

    @Override
    public List<ClassInfo> parseClasses(Path path) {
        if (!isValidPath(path)) return Collections.emptyList();
        return executeClassQuery(path);
    }

    @Override
    public List<MethodInfo> findMethodsByAnnotation(Path path, String annotationName) {
        // Tree-sitter can't reliably parse annotations; return empty.
        // Callers needing annotation search should use HybridJavaParser.
        logger.debug("findMethodsByAnnotation not supported by TreeSitterParser, returning empty");
        return Collections.emptyList();
    }

    @Override
    public List<CodeSmell> detectSmells(Path path) {
        logger.debug("detectSmells not supported by TreeSitterParser, returning empty");
        return Collections.emptyList();
    }

    @Override
    public String getLanguage() {
        return languageKey;
    }

    // -- internal: method queries --

    @SuppressWarnings("null")
    private List<MethodInfo> executeMethodQuery(Path path, String queryString, String expectedName) {
        List<MethodInfo> results = new ArrayList<>();
        try {
            ParsedFile pf = readAndParse(path);
            try (Query query = new Query(language, queryString);
                 QueryCursor cursor = new QueryCursor(query)) {
                cursor.findMatches(pf.tree.getRootNode()).forEach(match -> {
                    for (Node nameNode : match.findNodes("methodName")) {
                        String foundName = nameNode.getText();
                        if (expectedName != null && !foundName.equals(expectedName)) continue;
                        Node methodNode = nameNode.getParent().orElse(null);
                        if (methodNode == null) continue;
                        MethodInfo info = buildMethodInfo(methodNode, pf);
                        if (info != null) results.add(info);
                    }
                });
            }
        } catch (IOException ex) {
            logger.error("Error reading file {}: {}", path, ex.getMessage(), ex);
        } catch (Exception ex) {
            logger.error("Unexpected error parsing file {}: {}", path, ex.getMessage(), ex);
        }
        return results;
    }

    @SuppressWarnings("null")
    private List<ClassInfo> executeClassQuery(Path path) {
        List<ClassInfo> results = new ArrayList<>();
        try {
            ParsedFile pf = readAndParse(path);
            try (Query query = new Query(language, ALL_CLASSES_QUERY);
                 QueryCursor cursor = new QueryCursor(query)) {
                cursor.findMatches(pf.tree.getRootNode()).forEach(match -> {
                    for (Node nameNode : match.findNodes("className")) {
                        Node classNode = nameNode.getParent().orElse(null);
                        if (classNode == null) continue;
                        ClassInfo ci = buildClassInfo(classNode, pf);
                        if (ci != null) results.add(ci);
                    }
                });
            }
        } catch (IOException ex) {
            logger.error("Error reading file {}: {}", path, ex.getMessage(), ex);
        } catch (Exception ex) {
            logger.error("Unexpected error parsing file {}: {}", path, ex.getMessage(), ex);
        }
        return results;
    }

    // -- building models --

    private MethodInfo buildMethodInfo(Node methodNode, ParsedFile pf) {
        int startLine = methodNode.getStartPoint().row() + 1;
        int endLine = methodNode.getEndPoint().row() + 1;

        String name = extractChildText(methodNode, "name");
        String returnType = extractChildText(methodNode, "type");
        if (returnType.isEmpty()) returnType = "void";
        List<String> modifiers = extractModifiers(methodNode, pf.bytes);
        List<ParameterInfo> parameters = extractParameters(methodNode);
        String className = extractEnclosingClassName(methodNode);
        String content = extractContent(pf.lines, startLine, endLine);

        return MethodInfo.basic(name, className, startLine, endLine,
                returnType, modifiers, parameters, content);
    }

    private ClassInfo buildClassInfo(Node classNode, ParsedFile pf) {
        String name = extractChildText(classNode, "name");
        int startLine = classNode.getStartPoint().row() + 1;
        int endLine = classNode.getEndPoint().row() + 1;
        List<String> modifiers = extractModifiers(classNode, pf.bytes);

        Node bodyNode = classNode.getChildByFieldName("body").orElse(null);
        List<MethodInfo> methods = new ArrayList<>();
        if (bodyNode != null) {
            for (int i = 0; i < bodyNode.getChildCount(); i++) {
                Node child = bodyNode.getChild(i).orElse(null);
                if (child != null && "method_declaration".equals(child.getType())) {
                    MethodInfo mi = buildMethodInfo(child, pf);
                    if (mi != null) methods.add(mi);
                }
            }
        }

        String packageName = extractPackageName(classNode);
        boolean isInterface = "interface_declaration".equals(classNode.getType());
        return new ClassInfo(name, packageName, startLine, endLine,
                modifiers, methods, "", List.of(), List.of(), isInterface);
    }

    // -- node helpers --

    @SuppressWarnings("null")
    private String extractChildText(Node parent, String fieldName) {
        return parent.getChildByFieldName(fieldName)
                .map(Node::getText)
                .orElse("");
    }

    private List<String> extractModifiers(Node node, byte[] fileBytes) {
        List<String> mods = new ArrayList<>();
        for (int i = 0; i < node.getChildCount(); i++) {
            Node child = node.getChild(i).orElse(null);
            if (child != null && "modifiers".equals(child.getType())) {
                String modText = substringFromBytes(fileBytes,
                        child.getStartByte(), child.getEndByte()).trim();
                for (String mod : modText.split("\\s+")) {
                    if (!mod.isBlank() && !mod.startsWith("@")) {
                        mods.add(mod);
                    }
                }
                break;
            }
        }
        return mods;
    }

    private List<ParameterInfo> extractParameters(Node methodNode) {
        List<ParameterInfo> params = new ArrayList<>();
        Node paramsNode = methodNode.getChildByFieldName("parameters").orElse(null);
        if (paramsNode == null) return params;

        for (int i = 0; i < paramsNode.getChildCount(); i++) {
            Node child = paramsNode.getChild(i).orElse(null);
            if (child == null) continue;
            if ("formal_parameter".equals(child.getType()) || "spread_parameter".equals(child.getType())) {
                String type = child.getChildByFieldName("type").map(Node::getText).orElse("?");
                String pName = child.getChildByFieldName("name").map(Node::getText).orElse("?");
                params.add(new ParameterInfo(type, pName));
            }
        }
        return params;
    }

    private String extractEnclosingClassName(Node node) {
        Node current = node.getParent().orElse(null);
        while (current != null) {
            if (isTypeDeclaration(current)) {
                return current.getChildByFieldName("name")
                        .map(Node::getText).orElse("");
            }
            current = current.getParent().orElse(null);
        }
        return "";
    }

    private static boolean isTypeDeclaration(Node node) {
        return switch (node.getType()) {
            case "class_declaration", "interface_declaration",
                 "enum_declaration", "record_declaration" -> true;
            default -> false;
        };
    }

    private String extractPackageName(Node classNode) {
        Node root = classNode;
        while (root.getParent().isPresent()) {
            root = root.getParent().get();
        }
        for (int i = 0; i < root.getChildCount(); i++) {
            Node child = root.getChild(i).orElse(null);
            if (child != null && "package_declaration".equals(child.getType())) {
                for (int j = 0; j < child.getChildCount(); j++) {
                    Node sub = child.getChild(j).orElse(null);
                    if (sub != null && "scoped_identifier".equals(sub.getType())) {
                        return sub.getText();
                    }
                }
            }
        }
        return "";
    }

    private String extractContent(List<String> lines, int startLine, int endLine) {
        StringBuilder sb = new StringBuilder();
        for (int i = startLine - 1; i < Math.min(endLine, lines.size()); i++) {
            sb.append(lines.get(i)).append("\n");
        }
        return sb.toString();
    }

    private String substringFromBytes(byte[] bytes, int startByte, int endByte) {
        int safeStart = Math.max(0, Math.min(startByte, bytes.length));
        int safeEnd = Math.max(safeStart, Math.min(endByte, bytes.length));
        return new String(bytes, safeStart, safeEnd - safeStart, StandardCharsets.UTF_8);
    }

    // -- validation & I/O --

    private boolean isValidPath(Path path) {
        if (path == null || !Files.exists(path)) {
            logger.warn("File does not exist or is null: {}", path);
            return false;
        }
        return true;
    }

    private boolean isValidInput(Path path, String methodName) {
        if (!isValidPath(path)) return false;
        if (methodName == null || methodName.isBlank()) {
            logger.warn("Method name is null or blank");
            return false;
        }
        return true;
    }

    private ParsedFile readAndParse(Path path) throws IOException {
        byte[] fileBytes = Files.readAllBytes(path);
        String content = new String(fileBytes, StandardCharsets.UTF_8);
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        Tree tree = parser.parse(content, InputEncoding.UTF_8).orElseThrow();
        return new ParsedFile(fileBytes, lines, tree);
    }

    private boolean parameterTypesMatch(List<ParameterInfo> actual, List<String> expected) {
        if (actual.size() != expected.size()) return false;
        for (int i = 0; i < actual.size(); i++) {
            if (!actual.get(i).type().equals(expected.get(i))) return false;
        }
        return true;
    }

    private record ParsedFile(byte[] bytes, List<String> lines, Tree tree) {}
}