package com.jsrc.app.output;

import java.nio.file.Path;
import java.util.List;

import com.jsrc.app.parser.model.CallChain;
import com.jsrc.app.parser.model.CodeSmell;
import com.jsrc.app.parser.model.MethodCall;
import com.jsrc.app.parser.model.MethodInfo;

/**
 * Human-readable text formatter for CLI output.
 * Preserves the original output format from App.java.
 */
public class TextFormatter implements OutputFormatter {

    @Override
    public void printMethods(List<MethodInfo> methods, Path file, String methodName) {
        for (MethodInfo m : methods) {
            System.out.printf("%n[%s] %s:%d-%d%n",
                    m.className().isEmpty() ? file.getFileName() : m.className(),
                    file, m.startLine(), m.endLine());
            System.out.printf("  %s%n", m.signature());

            if (!m.annotations().isEmpty()) {
                System.out.printf("  Annotations: %s%n", m.annotations());
            }
            if (!m.thrownExceptions().isEmpty()) {
                System.out.printf("  Throws: %s%n", String.join(", ", m.thrownExceptions()));
            }
            if (!m.typeParameters().isEmpty()) {
                System.out.printf("  Type params: %s%n", String.join(", ", m.typeParameters()));
            }
            if (m.javadoc() != null) {
                String firstLine = m.javadoc().lines().findFirst().orElse("").trim();
                if (firstLine.startsWith("*")) firstLine = firstLine.substring(1).trim();
                System.out.printf("  Javadoc: %s%n", firstLine);
            }
        }
    }

    @Override
    public void printSmells(List<CodeSmell> smells, Path file) {
        if (smells.isEmpty()) return;

        System.out.printf("%n--- %s ---%n", file);
        for (CodeSmell smell : smells) {
            System.out.printf("  [%s] %s at line %d in %s%n    %s%n",
                    smell.severity(), smell.ruleId(), smell.line(),
                    smell.methodName().isEmpty() ? smell.className() : smell.methodName() + "()",
                    smell.message());
        }
    }

    @Override
    public void printCallChains(List<CallChain> chains, String methodName) {
        if (chains.isEmpty()) {
            System.out.printf("No call chains found for method '%s'.%n", methodName);
            return;
        }

        System.out.printf("Found %d call chain(s):%n", chains.size());
        for (int i = 0; i < chains.size(); i++) {
            CallChain chain = chains.get(i);
            System.out.printf("%n  Chain %d (depth %d):%n", i + 1, chain.depth());
            System.out.printf("    %s%n", chain.summary());
            for (MethodCall step : chain.steps()) {
                System.out.printf("    %s -> %s [line %d]%n",
                        step.caller().displayName(),
                        step.callee().displayName(),
                        step.line());
            }
        }
    }
}
