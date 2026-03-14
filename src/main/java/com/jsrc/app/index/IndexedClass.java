package com.jsrc.app.index;

import java.util.List;

/**
 * Compact class metadata stored in the index.
 *
 * @param name          simple class name
 * @param packageName   package declaration
 * @param startLine     start line in source
 * @param endLine       end line in source
 * @param isInterface   true if interface
 * @param isAbstract    true if abstract
 * @param superClass    direct superclass name
 * @param interfaces    implemented interface names
 * @param methods       method signatures indexed
 * @param annotations   annotation names on the class
 * @param imports       import statements in the file
 */
public record IndexedClass(
        String name,
        String packageName,
        int startLine,
        int endLine,
        boolean isInterface,
        boolean isAbstract,
        List<String> superClass,
        List<String> interfaces,
        List<IndexedMethod> methods,
        List<String> annotations,
        List<String> imports
) {
    public String qualifiedName() {
        return packageName.isEmpty() ? name : packageName + "." + name;
    }
}
