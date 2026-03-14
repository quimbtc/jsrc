package com.jsrc.app.parser.model;

import java.util.List;

/**
 * Represents a parsed class/interface with its metadata.
 * <p>
 * Fields populated by all parsers: name, packageName, startLine, endLine,
 * modifiers, methods.
 * <p>
 * Fields populated only by semantic parsers: superClass, interfaces,
 * annotations, isInterface.
 *
 * @param name         simple class name
 * @param packageName  package declaration (empty string if none)
 * @param startLine    1-based start line in source file
 * @param endLine      1-based end line in source file
 * @param modifiers    class modifiers (e.g. "public", "abstract")
 * @param methods      methods declared in this class
 * @param superClass   direct superclass name (empty if none or unknown)
 * @param interfaces   implemented interface names (empty if none or unknown)
 * @param annotations  annotations on this class (empty if not available)
 * @param isInterface  true if this is an interface rather than a class
 */
public record ClassInfo(
        String name,
        String packageName,
        int startLine,
        int endLine,
        List<String> modifiers,
        List<MethodInfo> methods,
        String superClass,
        List<String> interfaces,
        List<AnnotationInfo> annotations,
        boolean isInterface
) {
    /**
     * Convenience constructor for parsers that don't extract semantic fields.
     */
    public static ClassInfo basic(
            String name, String packageName, int startLine, int endLine,
            List<String> modifiers, List<MethodInfo> methods
    ) {
        return new ClassInfo(name, packageName, startLine, endLine,
                modifiers, methods, "", List.of(), List.of(), false);
    }

    public String qualifiedName() {
        if (packageName == null || packageName.isEmpty()) {
            return name;
        }
        return packageName + "." + name;
    }

    public boolean isAbstract() {
        return modifiers.contains("abstract");
    }
}
