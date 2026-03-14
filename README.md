# jsrc

A Java source code navigator built for AI agents. Uses [Tree-sitter](https://tree-sitter.github.io/) for speed and [JavaParser](https://javaparser.org/) for semantic depth to let agents explore large Java codebases without filling their context window with source code.

## Why jsrc?

An agent with ~200K tokens of context can't read a 10,000-file codebase. jsrc gives the agent structured navigation:

- **"What classes are in this codebase?"** → `jsrc --overview --json` (77ms for 8,323 files)
- **"Show me OrderService"** → `jsrc --summary OrderService --json`
- **"Who calls validate()?"** → `jsrc --callers validate --json`
- **"Does the code match the spec?"** → `jsrc --verify OrderService --spec spec.md --json`

All responses are compact JSON optimized for token efficiency.

## Performance

| Codebase | Files | `--overview` (no index) | `--overview` (with index) |
|----------|-------|------------------------|--------------------------|
| Small project | 51 | 1.7s | — |
| Spring Boot core | 1,621 | 145s | **41ms** |
| Spring Boot full | 8,323 | ~14min | **77ms** |

The persistent index makes all navigation commands sub-second on any codebase size.

## Quick Start

### Requirements

- Java 22+ ([SDKMAN](https://sdkman.io/) recommended)
- Maven 3.6.3+
- Tree-sitter native libraries (`libtree-sitter.so` + `libtree-sitter-java.so`)

### Build

```bash
mvn clean compile
```

### First use on a codebase

```bash
# 1. Index the codebase (one-time, auto-refreshes after)
jsrc /path/to/codebase --index

# 2. Explore
jsrc /path/to/codebase --overview --json
jsrc /path/to/codebase --classes --json
jsrc /path/to/codebase --summary MyService --json
```

If you run jsrc from the codebase root, the path is optional:

```bash
cd /path/to/codebase
jsrc --overview --json
```

## Commands

### Navigate

| Command | Description |
|---------|-------------|
| `--overview` | Codebase stats: files, classes, interfaces, methods, packages |
| `--classes` | List all classes/interfaces/enums/records |
| `--summary <Class>` | Class metadata + method signatures (no bodies) |
| `--hierarchy <Class>` | Inheritance tree: extends, implements, subclasses |
| `--implements <Interface>` | Find all implementors of an interface |
| `--deps <Class>` | Dependencies: imports, fields, constructor params |
| `--annotations <Name>` | Find all elements with a specific annotation |
| `--read <Class>` | Full source code of a class |
| `--read <Class.method>` | Source code of a specific method |

### Call Graph

| Command | Description |
|---------|-------------|
| `--callers <method>` | Who calls this method? (includes reflective calls) |
| `--callees <method>` | What does this method call? |
| `--call-chain <method>` | Full call chains from roots to target + Mermaid diagrams |

### Analyze

| Command | Description |
|---------|-------------|
| `--smells` | Detect code smells (9 rules) |
| `--check` | Evaluate architecture rules from `.jsrc.yaml` |
| `--check <ruleId>` | Evaluate a specific architecture rule |
| `--endpoints` | List REST endpoints (path, HTTP method, controller) |
| `--drift` | Combined architecture check + changed file detection |
| `--diff` | Files changed since last index (by content hash) |
| `--changed` | Java files changed in git (vs HEAD) |

### Reverse Engineering

| Command | Description |
|---------|-------------|
| `--context <Class> --json` | Full context package: summary + deps + hierarchy + call graph + smells + source |
| `--context <Class> --md` | Generate Markdown spec draft for the class |
| `--contract <Interface>` | Extract formal contract (methods, params, throws, javadoc) |
| `--verify <Class> --spec spec.md` | Compare implementation against Markdown spec |

### Meta

| Command | Description |
|---------|-------------|
| `--index` | Build/refresh persistent index |
| `--describe` | List all commands with args and flags (runtime introspection) |
| `--describe <command>` | Detail of a specific command |

## Global Flags

| Flag | Description |
|------|-------------|
| `--json` | Machine-readable JSON output (always use for agents) |
| `--md` | Markdown output (for `--context`) |
| `--metrics` | Append execution metrics to stderr |
| `--signature-only` | Compact method output (signature only, no body) |
| `--fields name,pkg` | Limit JSON to specific fields (saves tokens) |
| `--config <path>` | Use custom config file |

## Exit Codes

| Code | Meaning |
|------|---------|
| 0 | OK, results found |
| 1 | OK, no results matched |
| 2 | Bad arguments / unknown command |
| 3 | I/O error |

## Persistent Index

jsrc builds a persistent index (`.jsrc/index.json`) that makes all navigation commands instant:

```bash
jsrc --index                    # First run: parses all files (~14min for 8K files)
jsrc --overview --json          # Uses index: 77ms
# Edit some files...
jsrc --overview --json          # Auto-detects changes, re-parses only modified files
```

The index stores SHA-256 content hashes. On each command, jsrc compares file timestamps and only re-parses files that actually changed.

## Architecture Configuration

Create `.jsrc.yaml` in your project root:

```yaml
sourceRoots:
  - src/main/java
excludes:
  - "**/test/**"
  - "**/generated/**"
javaVersion: "22"

architecture:
  layers:
    - name: controller
      pattern: "**/*Controller"
      annotations: [RestController, Controller]
    - name: service
      pattern: "**/*Service"
      annotations: [Service]
    - name: repository
      pattern: "**/*Repository"
      annotations: [Repository]

  rules:
    - id: no-repo-in-controller
      description: "Controllers must not import repositories directly"
      from: controller
      denyImport: repository
    - id: constructor-injection
      description: "Services must use constructor injection"
      layer: service
      require: constructor-injection
      denyAnnotation: Autowired

  endpoints:
    - GetMapping
    - PostMapping
    - PutMapping
    - DeleteMapping
    - RequestMapping

  invokers:
    - method: ejecutarMetodo
      targetArg: 0
      resolveClass: adaptadorBean
      callerSuffixes: [Detalle, Vista, View, Form]
```

### Invoker Resolution

For codebases with reflective method invocation patterns (common in enterprise Java):

```java
// LiquidacionDetalle.java
ejecutarMetodo("calcularImporte", params);
```

jsrc resolves this to `LiquidacionAdaptadorBean.calcularImporte()` by:
1. Extracting the string literal from the configured argument position
2. Stripping the caller suffix ("Detalle") 
3. Appending the convention name ("AdaptadorBean")

These reflective calls appear in `--callers` output with `"type": "reflective"`.

## Reverse Engineering Workflow

```bash
# 1. Generate spec draft from code
jsrc --context OrderService --md > specs/OrderService.md

# 2. Agent or human refines the spec (fills TODOs, adds invariants)

# 3. Verify implementation matches spec
jsrc --verify OrderService --spec specs/OrderService.md --json
# → {"pass": true, "discrepancies": []}

# 4. After code changes, check for drift
jsrc --drift --json
```

## Code Smell Detection

9 built-in rules:

| Rule | Severity | Description |
|------|----------|-------------|
| `SWITCH_WITHOUT_DEFAULT` | WARNING | Switch without default case |
| `EMPTY_CATCH_BLOCK` | WARNING | Exception silently swallowed |
| `CATCH_GENERIC_EXCEPTION` | WARNING | Catching Exception/Throwable |
| `EMPTY_IF_BODY` | WARNING | Empty if statement body |
| `METHOD_TOO_LONG` | INFO | Method exceeds 30 lines |
| `TOO_MANY_PARAMETERS` | INFO | Method has more than 5 parameters |
| `DEEP_NESTING` | WARNING | Nesting depth exceeds 4 levels |
| `MAGIC_NUMBER` | INFO | Numeric literal (not 0, 1, -1) |
| `UNUSED_PARAMETER` | INFO | Parameter never used in method body |

## Project Structure

```
src/main/java/com/jsrc/app/
├── App.java                         CLI entry point + command dispatch
├── ExitCode.java                    Exit code constants
├── CommandRegistry.java             Command metadata for --describe
├── architecture/
│   ├── LayerResolver.java           Resolves class → architectural layer
│   ├── RuleEngine.java              Evaluates architecture rules
│   ├── Violation.java               Rule violation record
│   ├── EndpointMapper.java          REST endpoint discovery
│   └── InvokerResolver.java         Reflective call resolution
├── codebase/
│   ├── CodeBase.java                Codebase interface
│   ├── JavaCodeBase.java            Java implementation (lazy cache)
│   └── CodeBaseLoader.java          File discovery
├── config/
│   ├── ProjectConfig.java           .jsrc.yaml loader (SnakeYAML)
│   └── ArchitectureConfig.java      Architecture config records
├── index/
│   ├── CodebaseIndex.java           Persistent index (build/save/load)
│   ├── IndexedCodebase.java         Query index + auto-refresh
│   ├── IndexEntry.java              Per-file index entry
│   ├── IndexedClass.java            Indexed class metadata
│   └── IndexedMethod.java           Indexed method metadata
├── output/
│   ├── OutputFormatter.java         Formatter interface
│   ├── JsonFormatter.java           JSON output (agent-optimized)
│   ├── TextFormatter.java           Human-readable text output
│   ├── MarkdownFormatter.java       Spec Markdown generator
│   ├── JsonWriter.java              Minimal JSON serializer
│   ├── JsonReader.java              Minimal JSON parser
│   ├── FieldsFilter.java            --fields support
│   ├── ExecutionMetrics.java        Performance metrics
│   ├── AnnotationMatch.java         Annotation search result
│   ├── DependencyResult.java        Dependency analysis result
│   ├── HierarchyResult.java         Hierarchy query result
│   └── OverviewResult.java          Codebase overview result
├── parser/
│   ├── CodeParser.java              Parser interface
│   ├── TreeSitterParser.java        Fast syntax-level parsing
│   ├── HybridJavaParser.java        Tree-sitter + JavaParser hybrid
│   ├── TreeSitterLanguageFactory.java  Native library management
│   ├── CodeSmellDetector.java       9-rule static analysis
│   ├── CallGraphBuilder.java        Cross-file call graph
│   ├── CallChainTracer.java         DFS call chain tracing
│   ├── MermaidDiagramGenerator.java  Sequence diagram output
│   ├── ContextAssembler.java        Full context package builder
│   ├── SourceReader.java            Source code reader
│   ├── DependencyAnalyzer.java      Import/field/ctor analysis
│   └── model/                       Immutable records (8 types)
├── spec/
│   ├── SpecParser.java              Markdown spec parser
│   └── SpecVerifier.java            Spec vs implementation checker
└── util/
    ├── StopWatch.java               Performance timer
    └── InputValidator.java          Input hardening (anti-hallucination)
```

## Agent Integration

jsrc ships with a `SKILL.md` that agents can read to understand all commands, invariants, and best practices. See [SKILL.md](SKILL.md) for the complete agent guide.

Key invariants for agents:
1. Always use `--json` — text output is for humans
2. Run `--index` first on new codebases
3. Index auto-refreshes — no manual re-indexing needed after file changes
4. stdout = data, stderr = diagnostics
5. Use `--fields` to limit JSON and save tokens
6. Use `--metrics` to verify index is working (should be <1s)

## Test

```bash
mvn test    # 173 tests
```

## License

Licensed under the MIT License.
