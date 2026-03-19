package com.jsrc.app.analysis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.DoubleLiteralExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.jsrc.app.parser.model.CodeSmell;
import com.jsrc.app.parser.model.CodeSmell.Severity;

/**
 * Stateless detector that analyzes JavaParser AST nodes for common code smells.
 * Each detection rule is an independent private method.
 */
public class CodeSmellDetector {

    private static final int DEFAULT_MAX_METHOD_LINES = 30;
    private static final int DEFAULT_MAX_PARAMETERS = 5;
    private static final int DEFAULT_MAX_NESTING = 4;
    private static final Set<String> BENIGN_NUMBERS = Set.of("0", "1", "-1", "0L", "1L", "0.0", "1.0", "0.0f", "1.0f");
    private static final Set<String> GENERIC_EXCEPTIONS = Set.of("Exception", "Throwable", "RuntimeException");

    private final int maxMethodLines;
    private final int maxParameters;
    private final int maxNesting;

    public CodeSmellDetector() {
        this(DEFAULT_MAX_METHOD_LINES, DEFAULT_MAX_PARAMETERS, DEFAULT_MAX_NESTING);
    }

    public CodeSmellDetector(int maxMethodLines, int maxParameters, int maxNesting) {
        this.maxMethodLines = maxMethodLines;
        this.maxParameters = maxParameters;
        this.maxNesting = maxNesting;
    }

    /**
     * Analyzes all classes and methods in a compilation unit.
     */
    public List<CodeSmell> analyzeFile(CompilationUnit cu) {
        List<CodeSmell> smells = new ArrayList<>();
        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(cid -> {
            String className = cid.getNameAsString();
            cid.getMethods().forEach(md -> smells.addAll(analyzeMethod(md, className)));
        });
        return smells;
    }

    /**
     * Runs all detectors on a single method.
     */
    public List<CodeSmell> analyzeMethod(MethodDeclaration md, String className) {
        String methodName = md.getNameAsString();
        int line = md.getBegin().map(p -> p.line).orElse(-1);
        List<CodeSmell> smells = new ArrayList<>();

        smells.addAll(detectSwitchWithoutDefault(md, className, methodName));
        smells.addAll(detectEmptyCatchBlock(md, className, methodName));
        smells.addAll(detectCatchGenericException(md, className, methodName));
        smells.addAll(detectEmptyIfBody(md, className, methodName));
        smells.addAll(detectMethodTooLong(md, className, methodName, line));
        smells.addAll(detectTooManyParameters(md, className, methodName, line));
        smells.addAll(detectDeepNesting(md, className, methodName));
        smells.addAll(detectMagicNumbers(md, className, methodName));
        smells.addAll(detectUnusedParameters(md, className, methodName));

        return smells;
    }

    // ---- 1. Switch without default ----

    private List<CodeSmell> detectSwitchWithoutDefault(MethodDeclaration md,
                                                       String className, String methodName) {
        List<CodeSmell> smells = new ArrayList<>();
        for (SwitchStmt sw : md.findAll(SwitchStmt.class)) {
            if (sw.getEntries().stream().noneMatch(entry -> entry.getLabels().isEmpty())) {
                smells.add(new CodeSmell(
                        "SWITCH_WITHOUT_DEFAULT", Severity.WARNING,
                        "Switch statement has no default case",
                        sw.getBegin().map(p -> p.line).orElse(-1),
                        methodName, className));
            }
        }
        return smells;
    }

    // ---- 2. Empty catch block + silent failure patterns ----

    private List<CodeSmell> detectEmptyCatchBlock(MethodDeclaration md,
                                                   String className, String methodName) {
        List<CodeSmell> smells = new ArrayList<>();
        for (CatchClause cc : md.findAll(CatchClause.class)) {
            var statements = cc.getBody().getStatements();
            int line = cc.getBegin().map(p -> p.line).orElse(-1);

            if (statements.isEmpty()) {
                smells.add(new CodeSmell(
                        "EMPTY_CATCH_BLOCK", Severity.WARNING,
                        "Catch block is empty — exception is silently swallowed",
                        line, methodName, className));
                continue;
            }

            // Analyze all statements for silent failure patterns
            boolean hasPrintStackTrace = false;
            boolean hasContinue = false;
            boolean hasReturnNull = false;
            boolean hasReturn = false;
            boolean hasLogging = false;
            boolean hasRealHandling = false;

            for (var stmt : statements) {
                String s = stmt.toString().trim();
                if (s.contains("printStackTrace")) hasPrintStackTrace = true;
                else if (s.equals("continue;")) hasContinue = true;
                else if (s.equals("return null;")) hasReturnNull = true;
                else if (s.equals("return;")) hasReturn = true;
                else if (isLoggingStatement(s)) hasLogging = true;
                else hasRealHandling = true; // rethrow, wrap, set flag, etc.
            }

            // Only flag if there's NO real handling (rethrow, wrap, set flag)
            // Logging is NOT real handling — caller is still unaware
            if (!hasRealHandling) {
                if (hasLogging) {
                    smells.add(new CodeSmell(
                            "SILENT_CATCH", Severity.WARNING,
                            "Catch block logs but does not propagate error — caller unaware of failure",
                            line, methodName, className));
                }
                if (hasContinue) {
                    smells.add(new CodeSmell(
                            "SILENT_CATCH_CONTINUE", Severity.WARNING,
                            "Catch block silently continues — exception swallowed",
                            line, methodName, className));
                }
                if (hasReturnNull) {
                    smells.add(new CodeSmell(
                            "SILENT_CATCH_RETURN_NULL", Severity.WARNING,
                            "Catch block returns null — error hidden from caller",
                            line, methodName, className));
                }
                if (hasReturn && !hasReturnNull) {
                    smells.add(new CodeSmell(
                            "SILENT_CATCH_RETURN", Severity.INFO,
                            "Catch block only returns — exception silently consumed",
                            line, methodName, className));
                }
                if (hasPrintStackTrace) {
                    smells.add(new CodeSmell(
                            "CATCH_PRINT_STACKTRACE", Severity.WARNING,
                            "Using printStackTrace() instead of proper logging",
                            line, methodName, className));
                }
            }
        }
        return smells;
    }

    // ---- 3. Catch generic exception ----

    private List<CodeSmell> detectCatchGenericException(MethodDeclaration md,
                                                        String className, String methodName) {
        List<CodeSmell> smells = new ArrayList<>();
        for (CatchClause cc : md.findAll(CatchClause.class)) {
            String exType = cc.getParameter().getTypeAsString();
            if (GENERIC_EXCEPTIONS.contains(exType)) {
                smells.add(new CodeSmell(
                        "CATCH_GENERIC_EXCEPTION", Severity.WARNING,
                        "Catching generic " + exType + " — prefer specific exception types",
                        cc.getBegin().map(p -> p.line).orElse(-1),
                        methodName, className));
            }
        }
        return smells;
    }

    // ---- 4. Empty if body ----

    private List<CodeSmell> detectEmptyIfBody(MethodDeclaration md,
                                               String className, String methodName) {
        List<CodeSmell> smells = new ArrayList<>();
        for (IfStmt ifStmt : md.findAll(IfStmt.class)) {
            if (ifStmt.getThenStmt() instanceof BlockStmt block
                    && block.getStatements().isEmpty()) {
                smells.add(new CodeSmell(
                        "EMPTY_IF_BODY", Severity.WARNING,
                        "If statement has an empty body",
                        ifStmt.getBegin().map(p -> p.line).orElse(-1),
                        methodName, className));
            }
        }
        return smells;
    }

    // ---- 5. Method too long ----

    private List<CodeSmell> detectMethodTooLong(MethodDeclaration md,
                                                 String className, String methodName, int startLine) {
        int endLine = md.getEnd().map(p -> p.line).orElse(startLine);
        int length = endLine - startLine + 1;
        if (length > maxMethodLines) {
            List<CodeSmell> result = new ArrayList<>();
            result.add(new CodeSmell(
                    "METHOD_TOO_LONG", Severity.INFO,
                    "Method is " + length + " lines long (threshold: " + maxMethodLines + ")",
                    startLine, methodName, className));
            return result;
        }
        return Collections.emptyList();
    }

    // ---- 6. Too many parameters ----

    private List<CodeSmell> detectTooManyParameters(MethodDeclaration md,
                                                     String className, String methodName, int line) {
        int count = md.getParameters().size();
        if (count > maxParameters) {
            List<CodeSmell> result = new ArrayList<>();
            result.add(new CodeSmell(
                    "TOO_MANY_PARAMETERS", Severity.INFO,
                    "Method has " + count + " parameters (threshold: " + maxParameters + ")",
                    line, methodName, className));
            return result;
        }
        return Collections.emptyList();
    }

    // ---- 7. Deep nesting ----

    private List<CodeSmell> detectDeepNesting(MethodDeclaration md,
                                               String className, String methodName) {
        List<CodeSmell> smells = new ArrayList<>();
        md.getBody().ifPresent(body -> checkNestingDepth(body, 0, className, methodName, smells));
        return smells;
    }

    private void checkNestingDepth(Node node, int depth,
                                   String className, String methodName, List<CodeSmell> smells) {
        if (isNestingNode(node)) {
            depth++;
            if (depth > maxNesting) {
                smells.add(new CodeSmell(
                        "DEEP_NESTING", Severity.WARNING,
                        "Nesting depth is " + depth + " (threshold: " + maxNesting + ")",
                        node.getBegin().map(p -> p.line).orElse(-1),
                        methodName, className));
                return;
            }
        }
        for (Node child : node.getChildNodes()) {
            checkNestingDepth(child, depth, className, methodName, smells);
        }
    }

    private boolean isNestingNode(Node node) {
        return node instanceof IfStmt
                || node instanceof ForStmt
                || node instanceof ForEachStmt
                || node instanceof WhileStmt
                || node instanceof DoStmt
                || node instanceof TryStmt
                || node instanceof SwitchStmt;
    }

    // ---- 8. Magic numbers ----

    private List<CodeSmell> detectMagicNumbers(MethodDeclaration md,
                                                String className, String methodName) {
        List<CodeSmell> smells = new ArrayList<>();

        md.findAll(IntegerLiteralExpr.class).stream()
                .filter(lit -> !BENIGN_NUMBERS.contains(lit.getValue()))
                .filter(lit -> !isInsideConstantDeclaration(lit))
                .forEach(lit -> smells.add(new CodeSmell(
                        "MAGIC_NUMBER", Severity.INFO,
                        "Magic number " + lit.getValue() + " — consider extracting to a named constant",
                        lit.getBegin().map(p -> p.line).orElse(-1),
                        methodName, className)));

        md.findAll(LongLiteralExpr.class).stream()
                .filter(lit -> !BENIGN_NUMBERS.contains(lit.getValue()))
                .filter(lit -> !isInsideConstantDeclaration(lit))
                .forEach(lit -> smells.add(new CodeSmell(
                        "MAGIC_NUMBER", Severity.INFO,
                        "Magic number " + lit.getValue() + " — consider extracting to a named constant",
                        lit.getBegin().map(p -> p.line).orElse(-1),
                        methodName, className)));

        md.findAll(DoubleLiteralExpr.class).stream()
                .filter(lit -> !BENIGN_NUMBERS.contains(lit.getValue()))
                .filter(lit -> !isInsideConstantDeclaration(lit))
                .forEach(lit -> smells.add(new CodeSmell(
                        "MAGIC_NUMBER", Severity.INFO,
                        "Magic number " + lit.getValue() + " — consider extracting to a named constant",
                        lit.getBegin().map(p -> p.line).orElse(-1),
                        methodName, className)));

        return smells;
    }

    private boolean isInsideConstantDeclaration(Node node) {
        Node current = node.getParentNode().orElse(null);
        while (current != null) {
            if (current instanceof com.github.javaparser.ast.body.FieldDeclaration fd) {
                return fd.isStatic() && fd.isFinal();
            }
            current = current.getParentNode().orElse(null);
        }
        return false;
    }

    // ---- 9. Unused parameters ----

    private List<CodeSmell> detectUnusedParameters(MethodDeclaration md,
                                                    String className, String methodName) {
        if (md.getBody().isEmpty()) return Collections.emptyList();
        if (md.isAbstract()) return Collections.emptyList();
        if (md.getAnnotationByName("Override").isPresent()) return Collections.emptyList();

        BlockStmt body = md.getBody().get();
        Set<String> usedNames = body.findAll(NameExpr.class).stream()
                .map(NameExpr::getNameAsString)
                .collect(Collectors.toSet());

        List<CodeSmell> smells = new ArrayList<>();
        for (Parameter param : md.getParameters()) {
            if (!usedNames.contains(param.getNameAsString())) {
                smells.add(new CodeSmell(
                        "UNUSED_PARAMETER", Severity.INFO,
                        "Parameter '" + param.getNameAsString() + "' is never used",
                        param.getBegin().map(p -> p.line).orElse(-1),
                        methodName, className));
            }
        }
        return smells;
    }

    /**
     * Checks if a statement is a logging call (not real error handling).
     * Matches: logger.error(...), log.warn(...), System.err.println(...), LOG.info(...)
     */
    private static boolean isLoggingStatement(String stmt) {
        String lower = stmt.toLowerCase();
        return lower.contains("logger.") || lower.contains("log.") || lower.contains("log4j")
                || lower.contains("system.err.println") || lower.contains("system.out.println");
    }
}
