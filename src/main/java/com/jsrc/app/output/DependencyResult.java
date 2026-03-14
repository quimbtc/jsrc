package com.jsrc.app.output;

import java.util.List;

/**
 * Represents the dependencies of a class.
 *
 * @param className               queried class name
 * @param imports                  import statements
 * @param fieldDependencies        field types and names
 * @param constructorDependencies  constructor parameter types and names
 */
public record DependencyResult(
        String className,
        List<String> imports,
        List<FieldDep> fieldDependencies,
        List<FieldDep> constructorDependencies
) {
    public record FieldDep(String type, String name) {}
}
