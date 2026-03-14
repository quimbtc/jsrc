package com.jsrc.app.parser.model;

import java.util.List;

/**
 * Represents a method found during parsing, with full metadata.
 * <p>
 * Fields populated by all parsers: name, className, startLine, endLine,
 * returnType, modifiers, parameters, content.
 * <p>
 * Fields populated only by semantic parsers (e.g. HybridJavaParser):
 * annotations, thrownExceptions, typeParameters, javadoc.
 * Syntax-only parsers leave these as empty lists / null.
 *
 * @param name              method name
 * @param className         enclosing class name (empty if unknown)
 * @param startLine         1-based start line in source file
 * @param endLine           1-based end line in source file
 * @param returnType        declared return type as string
 * @param modifiers         access/other modifiers (e.g. "public", "static")
 * @param parameters        parameter declarations (type + name pairs)
 * @param content           raw source text of the method
 * @param annotations       annotations on this method (empty if not available)
 * @param thrownExceptions  declared thrown exception types (empty if not available)
 * @param typeParameters    generic type parameters (empty if not available)
 * @param javadoc           javadoc text (null if not available)
 */
public record MethodInfo(
        String name,
        String className,
        int startLine,
        int endLine,
        String returnType,
        List<String> modifiers,
        List<ParameterInfo> parameters,
        String content,
        List<AnnotationInfo> annotations,
        List<String> thrownExceptions,
        List<String> typeParameters,
        String javadoc
) {

    public record ParameterInfo(String type, String name) {
        @Override
        public String toString() {
            return type + " " + name;
        }
    }

    /**
     * Convenience constructor for parsers that don't extract semantic fields.
     */
    public static MethodInfo basic(
            String name, String className, int startLine, int endLine,
            String returnType, List<String> modifiers,
            List<ParameterInfo> parameters, String content
    ) {
        return new MethodInfo(name, className, startLine, endLine,
                returnType, modifiers, parameters, content,
                List.of(), List.of(), List.of(), null);
    }

    public boolean isAbstract() {
        return modifiers.contains("abstract");
    }

    public boolean isStatic() {
        return modifiers.contains("static");
    }

    public boolean hasAnnotation(String annotationName) {
        return annotations.stream().anyMatch(a -> a.name().equals(annotationName));
    }

    public String signature() {
        StringBuilder sb = new StringBuilder();
        if (!modifiers.isEmpty()) {
            sb.append(String.join(" ", modifiers)).append(" ");
        }
        if (returnType != null && !returnType.isEmpty()) {
            sb.append(returnType).append(" ");
        }
        sb.append(name).append("(");
        for (int i = 0; i < parameters.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(parameters.get(i));
        }
        sb.append(")");
        if (!thrownExceptions.isEmpty()) {
            sb.append(" throws ").append(String.join(", ", thrownExceptions));
        }
        return sb.toString();
    }
}
