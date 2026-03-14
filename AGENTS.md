# jsrc — Agent Rules

## Language

- Respond in Spanish unless the user writes in English.

## Code Style

- Java 21: use records, pattern matching, sealed interfaces, text blocks where appropriate.
- Follow standard Maven project layout (`src/main/java`, `src/test/java`).
- No commented-out code in production files. Delete it or put it behind a feature flag.
- No empty classes or placeholder files.
- Use `slf4j` for all logging. Never use `System.out` or `System.err` in library code.

## Architecture Principles

- **Interfaces return data**: parser methods must return model objects, not `void`.
- **Stateless services**: utilities like `CodeBaseLoader` must not accumulate state across calls.
- **Constructor injection**: pass dependencies and required configuration through constructors, not setters.
- **Immutable models**: use Java records for value objects (`MethodInfo`, `ClassInfo`, `ParseResult`).
- **Single responsibility**: keep native library loading in `TreeSitterLanguageFactory`, parsing logic in parser classes.

## Testing

- Tests must validate returned data, not just `assertDoesNotThrow`.
- Use `@TempDir` for file-system tests.
- No duplicate test classes across packages.
- Test class names must end with `Test`.

## Dependencies

- Keep `pom.xml` versions accurate and up to date.
- Use Maven properties for version management of multi-artifact dependencies.

## What NOT to do

- Do not duplicate classes to handle minor variations (use strategy/factory patterns instead).
- Do not leave `null` assignments where method calls should be (causes NPE).
- Do not mix Spanish and English in code identifiers — pick one per project and be consistent.
