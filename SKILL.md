# jsrc — Java Source Code Navigator for Agents

## What is jsrc?

A CLI tool that lets you navigate and inspect large Java codebases without reading source files. It parses code structure (classes, methods, annotations, inheritance, dependencies) and returns compact JSON optimized for LLM context windows.

## When to use jsrc

- You need to understand a Java codebase structure without reading every file
- You need to find a specific class, method, or annotation across thousands of files
- You need to trace call chains or understand class hierarchies
- You need to check dependencies or detect code smells

## When NOT to use jsrc

- Don't read `.java` files directly if jsrc can answer your question
- Don't parse jsrc text output — always use `--json`
- Don't skip `--index` on large codebases (>100 files) — without it, full-parse commands take minutes

## Required setup

```bash
# Java 21+ and Maven required
java -version
mvn --version

# Build jsrc
cd /path/to/jsrc
mvn clean compile
```

## Critical workflow

### Step 1: Index the codebase (do this FIRST)

```bash
jsrc /path/to/codebase --index
```

This parses all Java files and saves a persistent index to `.jsrc/index.json`. Only needed once — subsequent runs auto-refresh changed files.

- First run on 8,000 files: ~14 minutes
- Incremental (after changes): <2 seconds
- All query commands use the index automatically

### Step 2: Orient yourself

```bash
jsrc /path/to/codebase --overview --json
```

Returns: total files, classes, interfaces, methods, package list. ~77ms with index.

### Step 3: Query as needed

Always use `--json`. All commands work with or without explicit source root (defaults to `.`).

## Commands reference

### Navigate

```bash
# List all classes/interfaces
jsrc --classes --json

# Class summary (signatures without bodies)
jsrc --summary ClassName --json

# Class hierarchy (extends, implements, subclasses)
jsrc --hierarchy ClassName --json

# Find implementors of an interface
jsrc --implements InterfaceName --json

# Class dependencies (imports, fields, constructor params)
jsrc --deps ClassName --json

# Find annotated elements (methods + classes)
jsrc --annotations AnnotationName --json
```

### Search

```bash
# Find methods by name
jsrc methodName --json

# Find methods — signatures only (compact, saves tokens)
jsrc methodName --json --signature-only
```

### Analyze

```bash
# Detect code smells
jsrc --smells --json

# Trace call chains to a method (generates Mermaid diagrams)
jsrc --call-chain methodName --json
```

### Index

```bash
# Build/refresh index
jsrc --index

# Performance metrics for any command
jsrc --overview --json --metrics
```

## Invariants

1. **Always use `--json`** — text output is for humans, not agents
2. **Run `--index` first** on any new codebase — without it, navigation commands parse on-the-fly (slow)
3. **Index auto-refreshes** — if files changed since indexing, jsrc re-parses only those files automatically
4. **stdout = data, stderr = diagnostics** — parse stdout only
5. **`--signature-only`** saves tokens — use it when you don't need full method metadata
6. **`--metrics`** reports timing — use it to verify index is working (should be <1s)

## Output format (JSON)

All JSON output is compact (no pretty-print) to minimize tokens.

### --overview
```json
{"totalFiles":8323,"totalClasses":13335,"totalInterfaces":163,"totalMethods":12680,"totalPackages":124,"packages":["com.app","com.app.service"]}
```

### --classes
```json
[{"name":"OrderService","packageName":"com.app","qualifiedName":"com.app.OrderService","startLine":10,"endLine":50,"isInterface":false,"isAbstract":false,"methodCount":5}]
```

### --summary ClassName
```json
{"name":"OrderService","packageName":"com.app","qualifiedName":"com.app.OrderService","file":"src/main/java/com/app/OrderService.java","modifiers":["public"],"isInterface":false,"methods":[{"name":"create","signature":"public Order create(String name)","startLine":15,"returnType":"Order"}]}
```

### method search
```json
[{"name":"process","className":"Service","file":"Service.java","startLine":10,"endLine":25,"signature":"public void process(String input)","returnType":"void","modifiers":["public"],"parameters":[{"type":"String","name":"input"}]}]
```

### --metrics (stderr)
```json
{"command":"--overview","elapsedMs":77,"filesScanned":8323,"resultsFound":13335}
```

## Performance (with index)

| Command | 51 files | 1,621 files | 8,323 files |
|---------|----------|-------------|-------------|
| --overview | 1.7s | 41ms | 77ms |
| --classes | 1.5s | 146ms | 227ms |
| --annotations | 1.7s | 304ms | 857ms |
| --summary | 671ms | 39ms | 85ms |
| method search | 646ms | — | — |

Without index, full-parse commands on 8,323 files take 12+ minutes.

## Configuration (.jsrc.yaml)

Optional. Place in project root:

```yaml
sourceRoots:
  - src/main/java
  - src/generated/java
excludes:
  - "**/test/**"
  - "**/generated/**"
javaVersion: "21"
```

With config, source root argument is optional — jsrc uses `sourceRoots[0]` or pwd.
